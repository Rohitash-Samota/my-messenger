package com.rohitsamota.my_messenger.services;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.rohitsamota.my_messenger.dto.CreateConversationRequestDto;
import com.rohitsamota.my_messenger.dto.ConversionCursorResponseDto;
import com.rohitsamota.my_messenger.dto.ConversionResponseDto;
import com.rohitsamota.my_messenger.dto.UserSummaryDto;
import com.rohitsamota.my_messenger.entity.ConversationParticipant;
import com.rohitsamota.my_messenger.entity.Conversion;
import com.rohitsamota.my_messenger.entity.User;
import com.rohitsamota.my_messenger.enums.ConversionType;
import com.rohitsamota.my_messenger.enums.Status;
import com.rohitsamota.my_messenger.event.ConversationCreatedPayload;
import com.rohitsamota.my_messenger.event.EventEnvelope;
import com.rohitsamota.my_messenger.event.EventTypes;
import com.rohitsamota.my_messenger.repo.ConversationParticipantRepository;
import com.rohitsamota.my_messenger.repo.ConversionRepoI;
import com.rohitsamota.my_messenger.repo.UserInfoRepository;

@Service("conversionService")
public class ConversionService {
    private final ConversionRepoI conversionRepository;
    private final ConversationParticipantRepository participantRepository;
    private final UserInfoRepository userRepository;
    private final OutboxService outboxService;
    private final RealtimeEventPublisher realtimeEventPublisher;

    public ConversionService(
            ConversionRepoI conversionRepository,
            ConversationParticipantRepository participantRepository,
            UserInfoRepository userRepository,
            OutboxService outboxService,
            RealtimeEventPublisher realtimeEventPublisher) {
        this.conversionRepository = conversionRepository;
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.outboxService = outboxService;
        this.realtimeEventPublisher = realtimeEventPublisher;
    }

    @Transactional(readOnly = true)
    public ConversionCursorResponseDto listForUser(
            String email,
            String cursor,
            boolean archived,
            int limit) {
        Long userId = currentUserId(email);
        Cursor decodedCursor = decodeCursor(cursor);
        var pageRequest = PageRequest.of(0, limit + 1);
        List<Conversion> fetched = conversionRepository.findInboxPage(
                userId,
                archived,
                decodedCursor == null ? null : decodedCursor.pinned(),
                decodedCursor == null ? null : decodedCursor.activityAt(),
                decodedCursor == null ? null : decodedCursor.id(),
                pageRequest);

        boolean hasMore = fetched.size() > limit;
        List<Conversion> page = hasMore ? fetched.subList(0, limit) : fetched;
        Map<Long, ConversationParticipant> stateByConversion = participantStates(userId, page);
        Map<Long, User> peerById = directPeers(userId, page);
        List<ConversionResponseDto> items = page.stream()
                .map(conversion -> toResponse(
                        conversion,
                        requiredState(stateByConversion, conversion.getId()),
                        userId,
                        peerById))
                .toList();
        String nextCursor = hasMore ? encodeCursor(
                page.get(page.size() - 1),
                requiredState(stateByConversion, page.get(page.size() - 1).getId())) : null;

        return new ConversionCursorResponseDto(items, nextCursor, hasMore);
    }

    @Transactional
    public CreationResult createOrReuseDirect(
            String requesterEmail,
            CreateConversationRequestDto request) {
        User requester = activeCurrentUser(requesterEmail);
        String recipientEmail = request.recipientEmail().strip().toLowerCase(Locale.ROOT);
        User recipient = userRepository.findByEmailIgnoreCase(recipientEmail)
                .filter(user -> user.getStatus() == Status.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Recipient not found"));
        if (requester.getId().equals(recipient.getId())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "A direct conversation requires another user");
        }

        Long firstUserId = Math.min(requester.getId(), recipient.getId());
        Long secondUserId = Math.max(requester.getId(), recipient.getId());
        userRepository.findByIdForUpdate(firstUserId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "Conversation participant is unavailable"));
        userRepository.findByIdForUpdate(secondUserId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "Conversation participant is unavailable"));

        List<Conversion> matches = conversionRepository.findDirectBetween(
                firstUserId, secondUserId, PageRequest.of(0, 1));
        boolean created = matches.isEmpty();
        Conversion conversion;
        if (created) {
            conversion = new Conversion();
            conversion.setUserId(requester.getId());
            conversion.setClientId(recipient.getId());
            conversion.setConversionType(ConversionType.INDIVIDUAL);
            conversion = conversionRepository.saveAndFlush(conversion);
        } else {
            conversion = matches.get(0);
        }

        ConversationParticipant requesterState = ensureParticipant(
                conversion.getId(), requester.getId());
        ConversationParticipant recipientState = ensureParticipant(
                conversion.getId(), recipient.getId());
        participantRepository.saveAllAndFlush(List.of(requesterState, recipientState));

        if (created) {
            List<Long> participantIds = List.of(requester.getId(), recipient.getId());
            EventEnvelope<ConversationCreatedPayload> event = EventEnvelope.v1(
                    EventTypes.CONVERSATION_CREATED,
                    "CONVERSATION",
                    conversion.getId().toString(),
                    "conversation:" + conversion.getId() + ":created",
                    new ConversationCreatedPayload(
                            conversion.getId(),
                            requester.getId(),
                            participantIds,
                            Instant.now()));
            outboxService.enqueueConversationEvent(conversion.getId(), event);
            realtimeEventPublisher.publishAfterCommit(event, participantIds);
        }

        Map<Long, User> peerById = Map.of(recipient.getId(), recipient);
        return new CreationResult(
                toResponse(conversion, requesterState, requester.getId(), peerById),
                created);
    }

    @Transactional
    public ConversionResponseDto setPinned(String email, Long conversionId, boolean pinned) {
        Long userId = currentUserId(email);
        Conversion conversion = findVisibleConversion(conversionId, userId);
        ConversationParticipant participant = findActiveParticipant(conversionId, userId);
        participant.setPinned(pinned);
        syncLegacyOwnerFlags(conversion, participant, userId);
        return toResponse(conversion, participant, userId, directPeers(userId, List.of(conversion)));
    }

    @Transactional
    public ConversionResponseDto setArchived(String email, Long conversionId, boolean archived) {
        Long userId = currentUserId(email);
        Conversion conversion = findVisibleConversion(conversionId, userId);
        ConversationParticipant participant = findActiveParticipant(conversionId, userId);
        participant.setArchived(archived);
        syncLegacyOwnerFlags(conversion, participant, userId);
        return toResponse(conversion, participant, userId, directPeers(userId, List.of(conversion)));
    }

    private Conversion findVisibleConversion(Long conversionId, Long userId) {
        return conversionRepository.findVisibleById(conversionId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Conversion not found"));
    }

    private ConversationParticipant findActiveParticipant(Long conversionId, Long userId) {
        return participantRepository
                .findByConversionIdAndUserIdAndDeletedAtIsNull(conversionId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Conversion not found"));
    }

    private ConversationParticipant ensureParticipant(Long conversionId, Long userId) {
        ConversationParticipant participant = participantRepository
                .findByConversionIdAndUserId(conversionId, userId)
                .orElseGet(() -> new ConversationParticipant(conversionId, userId));
        if (!participant.isActive()) {
            participant.reactivate(LocalDateTime.now());
        }
        participant.show();
        return participant;
    }

    private void syncLegacyOwnerFlags(
            Conversion conversion,
            ConversationParticipant participant,
            Long userId) {
        if (userId.equals(conversion.getUserId())) {
            conversion.setIsPin(participant.isPinned());
            conversion.setIsArchive(participant.isArchived());
        }
    }

    private Map<Long, ConversationParticipant> participantStates(
            Long userId,
            List<Conversion> conversions) {
        if (conversions.isEmpty()) {
            return Map.of();
        }
        List<Long> conversionIds = conversions.stream().map(Conversion::getId).toList();
        return participantRepository
                .findByUserIdAndConversionIdInAndDeletedAtIsNull(userId, conversionIds)
                .stream()
                .collect(Collectors.toMap(
                        ConversationParticipant::getConversionId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private ConversationParticipant requiredState(
            Map<Long, ConversationParticipant> stateByConversion,
            Long conversionId) {
        ConversationParticipant participant = stateByConversion.get(conversionId);
        if (participant == null) {
            throw new IllegalStateException("Missing conversation participant projection");
        }
        return participant;
    }

    private String encodeCursor(
            Conversion conversion,
            ConversationParticipant participant) {
        String cursorValue = (participant.isPinned() ? "1" : "0")
                + "|" + conversion.getLastActivityAt()
                + "|" + conversion.getId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(cursorValue.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String encodedCursor) {
        if (encodedCursor == null || encodedCursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(encodedCursor),
                    StandardCharsets.UTF_8);
            String[] values = decoded.split("\\|", -1);
            if (values.length != 3 || (!values[0].equals("0") && !values[0].equals("1"))) {
                throw new IllegalArgumentException("Invalid cursor contents");
            }
            LocalDateTime activityAt = LocalDateTime.parse(values[1]);
            long id = Long.parseLong(values[2]);
            if (id <= 0) {
                throw new IllegalArgumentException("Cursor ID must be positive");
            }
            return new Cursor(values[0].equals("1"), activityAt, id);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid conversion cursor", exception);
        }
    }

    private ConversionResponseDto toResponse(
            Conversion conversion,
            ConversationParticipant participant,
            Long viewerUserId,
            Map<Long, User> peerById) {
        Long peerId = directPeerId(conversion, viewerUserId);
        UserSummaryDto peer = peerId == null || peerById.get(peerId) == null
                ? null
                : UserSummaryDto.from(peerById.get(peerId));
        return new ConversionResponseDto(
                conversion.getId(),
                conversion.getClientId(),
                conversion.getConversionType(),
                participant.isPinned(),
                participant.isArchived(),
                participant.getUnreadCount(),
                conversion.getLastMessageId(),
                conversion.getLastActivityAt(),
                peer);
    }

    private Map<Long, User> directPeers(Long viewerUserId, Collection<Conversion> conversions) {
        List<Long> peerIds = conversions.stream()
                .map(conversion -> directPeerId(conversion, viewerUserId))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (peerIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(peerIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private Long directPeerId(Conversion conversion, Long viewerUserId) {
        if (conversion.getConversionType() != ConversionType.INDIVIDUAL) {
            return null;
        }
        if (viewerUserId.equals(conversion.getUserId())) {
            return conversion.getClientId();
        }
        if (viewerUserId.equals(conversion.getClientId())) {
            return conversion.getUserId();
        }
        return null;
    }

    private User activeCurrentUser(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .filter(user -> user.getStatus() == Status.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "User not found"));
    }

    private Long currentUserId(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .map(user -> user.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "User not found"));
    }

    private record Cursor(boolean pinned, LocalDateTime activityAt, Long id) {
    }

    public record CreationResult(ConversionResponseDto conversation, boolean created) {
    }
}
