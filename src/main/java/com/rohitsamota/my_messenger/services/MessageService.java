package com.rohitsamota.my_messenger.services;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.rohitsamota.my_messenger.dto.MessageCursorResponseDto;
import com.rohitsamota.my_messenger.dto.MessageReceiptResponseDto;
import com.rohitsamota.my_messenger.dto.MessageResponseDto;
import com.rohitsamota.my_messenger.dto.MessageStateResponseDto;
import com.rohitsamota.my_messenger.dto.SendMessageRequestDto;
import com.rohitsamota.my_messenger.entity.ConversationParticipant;
import com.rohitsamota.my_messenger.entity.Conversion;
import com.rohitsamota.my_messenger.entity.Message;
import com.rohitsamota.my_messenger.entity.MessageReceipt;
import com.rohitsamota.my_messenger.enums.ConversionType;
import com.rohitsamota.my_messenger.enums.MessageStatus;
import com.rohitsamota.my_messenger.enums.MessageType;
import com.rohitsamota.my_messenger.event.EventEnvelope;
import com.rohitsamota.my_messenger.event.EventTypes;
import com.rohitsamota.my_messenger.event.MessageCreatedPayload;
import com.rohitsamota.my_messenger.event.MessageStateChangedPayload;
import com.rohitsamota.my_messenger.repo.ConversationParticipantRepository;
import com.rohitsamota.my_messenger.repo.ConversionRepoI;
import com.rohitsamota.my_messenger.repo.GroupMemberRepository;
import com.rohitsamota.my_messenger.repo.MessageReceiptRepository;
import com.rohitsamota.my_messenger.repo.MessageRepoI;
import com.rohitsamota.my_messenger.repo.UserInfoRepository;

@Service
public class MessageService {
    private final MessageRepoI messageRepository;
    private final MessageReceiptRepository receiptRepository;
    private final ConversionRepoI conversionRepository;
    private final ConversationParticipantRepository participantRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserInfoRepository userRepository;
    private final OutboxService outboxService;
    private final RealtimeEventPublisher realtimeEventPublisher;
    private final MediaStorageService mediaStorageService;

    public MessageService(
            MessageRepoI messageRepository,
            MessageReceiptRepository receiptRepository,
            ConversionRepoI conversionRepository,
            ConversationParticipantRepository participantRepository,
            GroupMemberRepository groupMemberRepository,
            UserInfoRepository userRepository,
            OutboxService outboxService,
            RealtimeEventPublisher realtimeEventPublisher,
            MediaStorageService mediaStorageService) {
        this.messageRepository = messageRepository;
        this.receiptRepository = receiptRepository;
        this.conversionRepository = conversionRepository;
        this.participantRepository = participantRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.userRepository = userRepository;
        this.outboxService = outboxService;
        this.realtimeEventPublisher = realtimeEventPublisher;
        this.mediaStorageService = mediaStorageService;
    }

    @Transactional(readOnly = true)
    public MessageCursorResponseDto listForConversion(
            String email,
            Long conversionId,
            Long cursor,
            int limit) {
        Long viewerUserId = currentUserId(email);
        Conversion conversion = conversionRepository.findById(conversionId)
                .orElseThrow(this::conversationNotFound);
        requireActiveMember(conversion, viewerUserId);

        var pageRequest = PageRequest.of(0, limit + 1);
        List<Message> fetched = cursor == null
                ? messageRepository.findByConversionIdOrderByIdDesc(conversionId, pageRequest)
                : messageRepository.findByConversionIdAndIdLessThanOrderByIdDesc(
                        conversionId, cursor, pageRequest);

        boolean hasMore = fetched.size() > limit;
        List<Message> page = hasMore ? fetched.subList(0, limit) : fetched;
        Map<Long, List<MessageReceipt>> receiptsByMessage = receiptsByMessage(page);
        List<MessageResponseDto> items = page.stream()
                .map(message -> toResponse(
                        message,
                        viewerUserId,
                        receiptsByMessage.getOrDefault(message.getId(), List.of())))
                .toList();
        Long nextCursor = hasMore ? page.get(page.size() - 1).getId() : null;

        return new MessageCursorResponseDto(items, nextCursor, hasMore);
    }

    @Transactional
    public MessageResponseDto send(
            String email,
            Long conversionId,
            SendMessageRequestDto request) {
        Long senderUserId = currentUserId(email);
        Conversion conversion = lockConversation(conversionId);
        requireActiveMember(conversion, senderUserId);

        String clientMessageId = request.clientMessageId().strip();
        String content = request.content().strip();
        Message existing = messageRepository
                .findByUserIdAndClientMessageId(senderUserId, clientMessageId)
                .orElse(null);
        if (existing != null) {
            assertIdempotentReplay(existing, conversionId, request, content);
            return responseForSender(existing);
        }

        validateMessageContent(conversionId, request.messageType(), content);

        if (request.parentMessageId() != null
                && messageRepository.findByIdAndConversionId(
                        request.parentMessageId(), conversionId).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Parent message does not belong to this conversion");
        }

        LinkedHashSet<Long> activeParticipantIds = activeParticipantIds(conversion);
        if (!activeParticipantIds.contains(senderUserId)) {
            throw conversationNotFound();
        }
        syncParticipantProjection(conversion, activeParticipantIds);

        List<Long> recipientUserIds = activeParticipantIds.stream()
                .filter(userId -> !userId.equals(senderUserId))
                .toList();
        if (recipientUserIds.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Conversion has no active recipients");
        }

        Message message = new Message();
        message.setUserId(senderUserId);
        message.setConversionId(conversionId);
        message.setClientMessageId(clientMessageId);
        message.setParentMessageId(request.parentMessageId());
        message.setContent(content);
        message.setMessageType(request.messageType());
        message.setStatus(MessageStatus.SENT);
        message = messageRepository.saveAndFlush(message);
        Long persistedMessageId = message.getId();

        List<MessageReceipt> receipts = recipientUserIds.stream()
                .map(recipientUserId -> new MessageReceipt(persistedMessageId, recipientUserId))
                .toList();
        receiptRepository.saveAll(receipts);
        int incremented = participantRepository.incrementUnreadForRecipients(
                conversionId, recipientUserIds);
        if (incremented != recipientUserIds.size()) {
            throw new IllegalStateException("Recipient projection changed while sending the message");
        }

        conversion.recordActivity(message.getId(), message.getCreatedAt());
        EventEnvelope<MessageCreatedPayload> event = EventEnvelope.v1(
                EventTypes.MESSAGE_CREATED,
                "CONVERSION",
                conversionId.toString(),
                clientMessageId,
                new MessageCreatedPayload(
                        message.getId(),
                        conversionId,
                        senderUserId,
                        recipientUserIds,
                        request.parentMessageId(),
                        request.messageType(),
                        content,
                        Instant.now()));
        outboxService.enqueueConversationEvent(conversionId, event);
        realtimeEventPublisher.publishAfterCommit(event, activeParticipantIds);

        return toResponse(message, senderUserId, receipts);
    }

    @Transactional
    public MessageStateResponseDto acknowledgeDelivered(
            String email,
            Long conversionId,
            Long requestedMessageId) {
        return advanceReceiptState(
                email, conversionId, requestedMessageId, MessageStatus.DELIVERED);
    }

    @Transactional
    public MessageStateResponseDto markRead(
            String email,
            Long conversionId,
            Long requestedMessageId) {
        return advanceReceiptState(email, conversionId, requestedMessageId, MessageStatus.READ);
    }

    private MessageStateResponseDto advanceReceiptState(
            String email,
            Long conversionId,
            Long requestedMessageId,
            MessageStatus targetStatus) {
        Long userId = currentUserId(email);
        Conversion conversion = lockConversation(conversionId);
        requireActiveMember(conversion, userId);

        Message boundary = resolveBoundaryMessage(conversionId, requestedMessageId);
        if (boundary == null) {
            return new MessageStateResponseDto(
                    conversionId, null, targetStatus, 0, 0);
        }

        ConversationParticipant participant = activeParticipantForUpdate(conversion, userId);
        LocalDateTime changedAt = LocalDateTime.now();
        int updated;
        if (targetStatus == MessageStatus.DELIVERED) {
            updated = receiptRepository.markDeliveredThrough(
                    conversionId, userId, boundary.getId(), changedAt);
            participant.recordDeliveredThrough(boundary.getId());
        } else if (targetStatus == MessageStatus.READ) {
            updated = receiptRepository.markReadThrough(
                    conversionId, userId, boundary.getId(), changedAt);
            long remainingUnread = receiptRepository.countUnread(conversionId, userId);
            participant.recordReadThrough(boundary.getId(), remainingUnread);
        } else {
            throw new IllegalArgumentException("Unsupported receipt state: " + targetStatus);
        }

        messageRepository.recomputeAggregateStatusesThrough(
                conversionId, boundary.getId(), changedAt);
        long unreadCount = receiptRepository.countUnread(conversionId, userId);

        if (updated > 0) {
            EventEnvelope<MessageStateChangedPayload> event = EventEnvelope.v1(
                    EventTypes.MESSAGE_STATE_CHANGED,
                    "CONVERSION",
                    conversionId.toString(),
                    conversionId + ":" + userId + ":" + targetStatus + ":" + boundary.getId(),
                    new MessageStateChangedPayload(
                            conversionId,
                            userId,
                            boundary.getId(),
                            targetStatus,
                            Instant.now()));
            outboxService.enqueueConversationEvent(conversionId, event);
            realtimeEventPublisher.publishAfterCommit(
                    event,
                    activeParticipantIds(conversion));
        }

        return new MessageStateResponseDto(
                conversionId,
                boundary.getId(),
                targetStatus,
                updated,
                unreadCount);
    }

    private Message resolveBoundaryMessage(Long conversionId, Long requestedMessageId) {
        if (requestedMessageId == null) {
            return messageRepository.findTopByConversionIdOrderByIdDesc(conversionId)
                    .orElse(null);
        }
        return messageRepository.findByIdAndConversionId(requestedMessageId, conversionId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Message does not belong to this conversion"));
    }

    private ConversationParticipant activeParticipantForUpdate(
            Conversion conversion,
            Long userId) {
        return participantRepository.findActiveForUpdate(conversion.getId(), userId)
                .orElseGet(() -> {
                    ConversationParticipant participant = participantRepository
                            .findByConversionIdAndUserId(conversion.getId(), userId)
                            .orElseGet(() -> new ConversationParticipant(
                                    conversion.getId(), userId));
                    if (!participant.isActive()) {
                        participant.reactivate(LocalDateTime.now());
                    }
                    participant.show();
                    return participantRepository.save(participant);
                });
    }

    private void syncParticipantProjection(Conversion conversion, Set<Long> activeUserIds) {
        List<ConversationParticipant> currentlyActive =
                participantRepository.findByConversionIdAndDeletedAtIsNullOrderByIdAsc(
                        conversion.getId());
        Map<Long, ConversationParticipant> activeByUser = currentlyActive.stream()
                .collect(Collectors.toMap(
                        ConversationParticipant::getUserId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));
        List<ConversationParticipant> changed = new ArrayList<>();

        for (Long activeUserId : activeUserIds) {
            if (!userRepository.existsById(activeUserId)) {
                throw new IllegalStateException(
                        "Conversion references a user that no longer exists: " + activeUserId);
            }
            ConversationParticipant participant = activeByUser.get(activeUserId);
            if (participant == null) {
                participant = participantRepository
                        .findByConversionIdAndUserId(conversion.getId(), activeUserId)
                        .orElseGet(() -> new ConversationParticipant(
                                conversion.getId(), activeUserId));
                if (!participant.isActive()) {
                    participant.reactivate(LocalDateTime.now());
                }
            }
            participant.show();
            changed.add(participant);
        }

        if (conversion.getConversionType() == ConversionType.GROUP) {
            for (ConversationParticipant participant : currentlyActive) {
                if (!activeUserIds.contains(participant.getUserId())) {
                    participant.leave(LocalDateTime.now());
                    changed.add(participant);
                }
            }
        }
        participantRepository.saveAllAndFlush(changed);
    }

    private LinkedHashSet<Long> activeParticipantIds(Conversion conversion) {
        LinkedHashSet<Long> userIds = new LinkedHashSet<>();
        if (conversion.getConversionType() == ConversionType.INDIVIDUAL) {
            userIds.add(conversion.getUserId());
            userIds.add(conversion.getClientId());
        } else if (conversion.getConversionType() == ConversionType.GROUP) {
            userIds.addAll(groupMemberRepository.findActiveUserIdsByGroupId(
                    conversion.getClientId()));
        } else {
            throw new IllegalStateException("Unsupported conversion type");
        }
        userIds.remove(null);
        return userIds;
    }

    private void requireActiveMember(Conversion conversion, Long userId) {
        boolean allowed = conversion.getConversionType() == ConversionType.INDIVIDUAL
                ? userId.equals(conversion.getUserId()) || userId.equals(conversion.getClientId())
                : conversion.getConversionType() == ConversionType.GROUP
                        && groupMemberRepository.existsByGroupIdAndUserIdAndDeletedAtIsNull(
                                conversion.getClientId(), userId);
        if (!allowed) {
            throw conversationNotFound();
        }
    }

    private Conversion lockConversation(Long conversionId) {
        return conversionRepository.findByIdForUpdate(conversionId)
                .orElseThrow(this::conversationNotFound);
    }

    private Long currentUserId(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .map(user -> user.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "User not found"));
    }

    private void assertIdempotentReplay(
            Message existing,
            Long conversionId,
            SendMessageRequestDto request,
            String normalizedContent) {
        boolean sameRequest = Objects.equals(existing.getConversionId(), conversionId)
                && Objects.equals(existing.getContent(), normalizedContent)
                && existing.getMessageType() == request.messageType()
                && Objects.equals(existing.getParentMessageId(), request.parentMessageId());
        if (!sameRequest) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "clientMessageId was already used for a different message");
        }
    }

    private void validateMessageContent(
            Long conversionId,
            MessageType messageType,
            String content) {
        if (messageType == MessageType.TEXT) {
            return;
        }
        if (messageType == MessageType.DOCUMENT) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Document uploads are not supported");
        }
        mediaStorageService.validateMessageReference(conversionId, messageType, content);
    }

    private MessageResponseDto responseForSender(Message message) {
        return toResponse(
                message,
                message.getUserId(),
                receiptRepository.findByMessageIdOrderByUserIdAsc(message.getId()));
    }

    private Map<Long, List<MessageReceipt>> receiptsByMessage(Collection<Message> messages) {
        if (messages.isEmpty()) {
            return Map.of();
        }
        List<Long> messageIds = messages.stream().map(Message::getId).toList();
        return receiptRepository.findByMessageIdInOrderByMessageIdAscUserIdAsc(messageIds)
                .stream()
                .collect(Collectors.groupingBy(
                        MessageReceipt::getMessageId,
                        LinkedHashMap::new,
                        Collectors.toList()));
    }

    private MessageResponseDto toResponse(
            Message message,
            Long viewerUserId,
            List<MessageReceipt> allReceipts) {
        List<MessageReceiptResponseDto> visibleReceipts = allReceipts.stream()
                .filter(receipt -> message.getUserId().equals(viewerUserId)
                        || receipt.getUserId().equals(viewerUserId))
                .map(receipt -> new MessageReceiptResponseDto(
                        receipt.getUserId(),
                        receipt.getStatus(),
                        receipt.getDeliveredAt(),
                        receipt.getReadAt()))
                .toList();
        return new MessageResponseDto(
                message.getId(),
                message.getConversionId(),
                message.getUserId(),
                message.getParentMessageId(),
                message.getClientMessageId(),
                message.getContent(),
                message.getMessageType(),
                message.getStatus(),
                message.getCreatedAt(),
                visibleReceipts);
    }

    private ResponseStatusException conversationNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversion not found");
    }
}
