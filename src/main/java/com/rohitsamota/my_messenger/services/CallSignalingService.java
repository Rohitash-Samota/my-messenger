package com.rohitsamota.my_messenger.services;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.rohitsamota.my_messenger.dto.CallSignalRequestDto;
import com.rohitsamota.my_messenger.dto.CallSignalResponseDto;
import com.rohitsamota.my_messenger.entity.User;
import com.rohitsamota.my_messenger.enums.CallMediaType;
import com.rohitsamota.my_messenger.enums.CallSignalType;
import com.rohitsamota.my_messenger.enums.ConversionType;
import com.rohitsamota.my_messenger.enums.Status;
import com.rohitsamota.my_messenger.repo.ConversationParticipantRepository;
import com.rohitsamota.my_messenger.repo.ConversionRepoI;
import com.rohitsamota.my_messenger.repo.UserInfoRepository;

@Service
public class CallSignalingService {
    private static final long CALL_TTL_MS = 2 * 60 * 60 * 1000L;
    private static final long INVITE_WINDOW_MS = 60_000L;
    private static final int MAX_INVITES_PER_WINDOW = 10;

    private final UserInfoRepository userRepository;
    private final ConversionRepoI conversionRepository;
    private final ConversationParticipantRepository participantRepository;
    private final ConcurrentMap<UUID, ActiveCall> activeCalls = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, InviteWindow> inviteWindows = new ConcurrentHashMap<>();

    public CallSignalingService(
            UserInfoRepository userRepository,
            ConversionRepoI conversionRepository,
            ConversationParticipantRepository participantRepository) {
        this.userRepository = userRepository;
        this.conversionRepository = conversionRepository;
        this.participantRepository = participantRepository;
    }

    @Transactional(readOnly = true)
    public RoutedSignal authorizeAndRoute(
            String senderEmail,
            String senderSessionId,
            CallSignalRequestDto request) {
        if (senderSessionId == null || senderSessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "WebSocket session is required");
        }
        validateSignalPayload(request);
        evictExpiredCalls();
        User sender = requireActiveUser(senderEmail);

        if (request.type() == CallSignalType.INVITE) {
            return openCall(sender, senderSessionId, request);
        }

        ActiveCall call = activeCalls.get(request.callId());
        if (call == null || call.isExpired()) {
            if (call != null) {
                activeCalls.remove(request.callId(), call);
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Call is no longer active");
        }

        synchronized (call) {
            if (!call.conversationId.equals(request.conversationId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Call does not belong to this conversation");
            }
            if (request.mediaType() != null && request.mediaType() != call.mediaType) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Call media type cannot change");
            }
            call.touch();
            return routeExistingCall(call, sender, senderSessionId, request);
        }
    }

    private RoutedSignal openCall(
            User sender,
            String senderSessionId,
            CallSignalRequestDto request) {
        enforceInviteRate(sender.getEmail());
        var conversion = conversionRepository.findVisibleById(request.conversationId(), sender.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        if (conversion.getDeletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
        }
        if (conversion.getConversionType() != ConversionType.INDIVIDUAL) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Peer-to-peer calls are available only for individual conversations");
        }

        List<Long> participantIds = participantRepository
                .findActiveUserIdsByConversionId(request.conversationId());
        if (participantIds.size() != 2 || !participantIds.contains(sender.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Conversation cannot start a direct call");
        }
        Long recipientId = participantIds.stream()
                .filter(userId -> !userId.equals(sender.getId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Call recipient is unavailable"));
        User recipient = userRepository.findById(recipientId)
                .filter(user -> user.getStatus() == Status.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Call recipient is unavailable"));

        ActiveCall call = new ActiveCall(
                request.callId(),
                request.conversationId(),
                request.mediaType(),
                sender.getId(),
                sender.getEmail(),
                senderSessionId,
                recipient.getId(),
                recipient.getEmail());
        if (activeCalls.putIfAbsent(request.callId(), call) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Call id is already in use");
        }
        return route(recipient.getEmail(), null, response(sender, call, request));
    }

    private RoutedSignal routeExistingCall(
            ActiveCall call,
            User sender,
            String senderSessionId,
            CallSignalRequestDto request) {
        boolean caller = call.callerUserId.equals(sender.getId())
                && call.callerSessionId.equals(senderSessionId);
        boolean recipientUser = call.recipientUserId.equals(sender.getId());
        boolean acceptedRecipient = recipientUser
                && call.recipientSessionId != null
                && call.recipientSessionId.equals(senderSessionId);

        return switch (request.type()) {
            case RINGING -> {
                require(recipientUser && call.phase == CallPhase.RINGING,
                        "Only the invited recipient can ring this call");
                yield route(call.callerEmail, call.callerSessionId, response(sender, call, request));
            }
            case ACCEPT -> {
                require(recipientUser && call.phase == CallPhase.RINGING,
                        "This call can no longer be accepted");
                if (call.recipientSessionId != null && !call.recipientSessionId.equals(senderSessionId)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Call was accepted on another device");
                }
                call.recipientSessionId = senderSessionId;
                call.phase = CallPhase.ACCEPTED;
                yield route(call.callerEmail, call.callerSessionId, response(sender, call, request));
            }
            case REJECT, BUSY -> {
                require(recipientUser && call.phase == CallPhase.RINGING,
                        "Only the invited recipient can decline this call");
                activeCalls.remove(call.callId, call);
                yield route(call.callerEmail, call.callerSessionId, response(sender, call, request));
            }
            case OFFER -> {
                require(caller && (call.phase == CallPhase.ACCEPTED || call.phase == CallPhase.ACTIVE),
                        "Caller cannot send an offer in the current call state");
                require(call.recipientSessionId != null, "Call has not been accepted");
                call.phase = CallPhase.OFFERED;
                yield route(call.recipientEmail, call.recipientSessionId, response(sender, call, request));
            }
            case ANSWER -> {
                require(acceptedRecipient && call.phase == CallPhase.OFFERED,
                        "Recipient cannot answer in the current call state");
                call.phase = CallPhase.ACTIVE;
                yield route(call.callerEmail, call.callerSessionId, response(sender, call, request));
            }
            case ICE_CANDIDATE -> {
                require(call.phase == CallPhase.ACCEPTED
                                || call.phase == CallPhase.OFFERED
                                || call.phase == CallPhase.ACTIVE,
                        "ICE candidates are not accepted before the call is answered");
                if (caller) {
                    require(call.recipientSessionId != null, "Call has not been accepted");
                    yield route(call.recipientEmail, call.recipientSessionId, response(sender, call, request));
                }
                require(acceptedRecipient, "Signal came from a different recipient session");
                yield route(call.callerEmail, call.callerSessionId, response(sender, call, request));
            }
            case HANGUP -> {
                require(caller || acceptedRecipient, "Only a bound call participant can end this call");
                activeCalls.remove(call.callId, call);
                if (caller) {
                    yield route(call.recipientEmail, call.recipientSessionId, response(sender, call, request));
                }
                yield route(call.callerEmail, call.callerSessionId, response(sender, call, request));
            }
            case INVITE -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Call is already active");
        };
    }

    private User requireActiveUser(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .filter(user -> user.getStatus() == Status.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is unavailable"));
    }

    private CallSignalResponseDto response(
            User sender,
            ActiveCall call,
            CallSignalRequestDto request) {
        String sdp = request.type() == CallSignalType.OFFER || request.type() == CallSignalType.ANSWER
                ? request.sdp()
                : null;
        var candidate = request.type() == CallSignalType.ICE_CANDIDATE
                ? request.candidate()
                : null;
        return new CallSignalResponseDto(
                call.callId,
                call.conversationId,
                sender.getId(),
                sender.getEmail(),
                request.type(),
                call.mediaType,
                sdp,
                candidate,
                Instant.now());
    }

    private RoutedSignal route(
            String recipientEmail,
            String recipientSessionId,
            CallSignalResponseDto signal) {
        return new RoutedSignal(recipientEmail, recipientSessionId, signal);
    }

    private void validateSignalPayload(CallSignalRequestDto request) {
        CallSignalType type = request.type();
        if (type == CallSignalType.INVITE && request.mediaType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Call media type is required");
        }
        if ((type == CallSignalType.OFFER || type == CallSignalType.ANSWER)
                && (request.sdp() == null || request.sdp().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Session description is required");
        }
        if (type == CallSignalType.ICE_CANDIDATE && request.candidate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ICE candidate is required");
        }
        if (type != CallSignalType.OFFER && type != CallSignalType.ANSWER
                && request.sdp() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Session description is not valid for this signal");
        }
        if (type != CallSignalType.ICE_CANDIDATE && request.candidate() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ICE candidate is not valid for this signal");
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, message);
        }
    }

    private void enforceInviteRate(String email) {
        long now = System.currentTimeMillis();
        InviteWindow window = inviteWindows.computeIfAbsent(email, ignored -> new InviteWindow());
        synchronized (window) {
            while (!window.timestamps.isEmpty()
                    && window.timestamps.peekFirst() < now - INVITE_WINDOW_MS) {
                window.timestamps.removeFirst();
            }
            if (window.timestamps.size() >= MAX_INVITES_PER_WINDOW) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many call attempts");
            }
            window.timestamps.addLast(now);
        }
    }

    private void evictExpiredCalls() {
        activeCalls.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    public void cancel(UUID callId) {
        if (callId != null) {
            activeCalls.remove(callId);
        }
    }

    public record RoutedSignal(
            String recipientEmail,
            String recipientSessionId,
            CallSignalResponseDto signal) {
    }

    private enum CallPhase {
        RINGING,
        ACCEPTED,
        OFFERED,
        ACTIVE
    }

    private static final class ActiveCall {
        private final UUID callId;
        private final Long conversationId;
        private final CallMediaType mediaType;
        private final Long callerUserId;
        private final String callerEmail;
        private final String callerSessionId;
        private final Long recipientUserId;
        private final String recipientEmail;
        private String recipientSessionId;
        private CallPhase phase = CallPhase.RINGING;
        private long touchedAt = System.currentTimeMillis();

        private ActiveCall(
                UUID callId,
                Long conversationId,
                CallMediaType mediaType,
                Long callerUserId,
                String callerEmail,
                String callerSessionId,
                Long recipientUserId,
                String recipientEmail) {
            this.callId = callId;
            this.conversationId = conversationId;
            this.mediaType = mediaType;
            this.callerUserId = callerUserId;
            this.callerEmail = callerEmail;
            this.callerSessionId = callerSessionId;
            this.recipientUserId = recipientUserId;
            this.recipientEmail = recipientEmail;
        }

        private void touch() {
            touchedAt = System.currentTimeMillis();
        }

        private boolean isExpired() {
            return touchedAt < System.currentTimeMillis() - CALL_TTL_MS;
        }
    }

    private static final class InviteWindow {
        private final Deque<Long> timestamps = new ArrayDeque<>();
    }
}
