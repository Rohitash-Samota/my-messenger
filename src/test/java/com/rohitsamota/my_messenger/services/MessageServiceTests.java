package com.rohitsamota.my_messenger.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.rohitsamota.my_messenger.dto.EditMessageRequestDto;
import com.rohitsamota.my_messenger.dto.MessageResponseDto;
import com.rohitsamota.my_messenger.dto.MessageStateResponseDto;
import com.rohitsamota.my_messenger.dto.SendMessageRequestDto;
import com.rohitsamota.my_messenger.entity.ConversationParticipant;
import com.rohitsamota.my_messenger.entity.Conversion;
import com.rohitsamota.my_messenger.entity.Message;
import com.rohitsamota.my_messenger.entity.MessageReceipt;
import com.rohitsamota.my_messenger.entity.User;
import com.rohitsamota.my_messenger.enums.ConversionType;
import com.rohitsamota.my_messenger.enums.MessageStatus;
import com.rohitsamota.my_messenger.enums.MessageType;
import com.rohitsamota.my_messenger.event.EventEnvelope;
import com.rohitsamota.my_messenger.event.EventTypes;
import com.rohitsamota.my_messenger.event.MessageCreatedPayload;
import com.rohitsamota.my_messenger.event.MessageMutationPayload;
import com.rohitsamota.my_messenger.repo.ConversationParticipantRepository;
import com.rohitsamota.my_messenger.repo.ConversionRepoI;
import com.rohitsamota.my_messenger.repo.GroupMemberRepository;
import com.rohitsamota.my_messenger.repo.MessageReceiptRepository;
import com.rohitsamota.my_messenger.repo.MessageRepoI;
import com.rohitsamota.my_messenger.repo.UserInfoRepository;

@ExtendWith(MockitoExtension.class)
class MessageServiceTests {
    @Mock
    private MessageRepoI messageRepository;
    @Mock
    private MessageReceiptRepository receiptRepository;
    @Mock
    private ConversionRepoI conversionRepository;
    @Mock
    private ConversationParticipantRepository participantRepository;
    @Mock
    private GroupMemberRepository groupMemberRepository;
    @Mock
    private UserInfoRepository userRepository;
    @Mock
    private OutboxService outboxService;
    @Mock
    private RealtimeEventPublisher realtimeEventPublisher;
    @Mock
    private MediaStorageService mediaStorageService;

    private MessageService messageService;

    @BeforeEach
    void setUp() {
        messageService = new MessageService(
                messageRepository,
                receiptRepository,
                conversionRepository,
                participantRepository,
                groupMemberRepository,
                userRepository,
                outboxService,
                realtimeEventPublisher,
                mediaStorageService);
    }

    @Test
    void sendsDirectMessageToRecipientSnapshotAndEnqueuesEvent() {
        User sender = user(1L, "sender@example.com");
        Conversion conversion = directConversion(10L, 1L, 2L);
        ConversationParticipant senderState = new ConversationParticipant(10L, 1L);
        ConversationParticipant recipientState = new ConversationParticipant(10L, 2L);

        when(userRepository.findByEmailIgnoreCase(sender.getEmail())).thenReturn(Optional.of(sender));
        when(conversionRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(conversion));
        when(messageRepository.findByUserIdAndClientMessageId(1L, "client-1"))
                .thenReturn(Optional.empty());
        when(participantRepository.findByConversionIdAndDeletedAtIsNullOrderByIdAsc(10L))
                .thenReturn(List.of(senderState, recipientState));
        when(userRepository.existsById(1L)).thenReturn(true);
        when(userRepository.existsById(2L)).thenReturn(true);
        when(messageRepository.saveAndFlush(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.setId(100L);
            message.setCreatedAt(LocalDateTime.of(2026, 10, 3, 12, 0));
            return message;
        });
        when(participantRepository.incrementUnreadForRecipients(10L, List.of(2L)))
                .thenReturn(1);

        MessageResponseDto response = messageService.send(
                sender.getEmail(),
                10L,
                new SendMessageRequestDto(" client-1 ", " hello ", MessageType.TEXT, null));

        assertEquals(100L, response.id());
        assertEquals("hello", response.content());
        assertEquals(List.of(2L), response.receipts().stream().map(r -> r.userId()).toList());
        assertEquals(100L, conversion.getLastMessageId());
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).saveAndFlush(messageCaptor.capture());
        assertEquals(sha256("hello"), messageCaptor.getValue().getOriginalContentSha256());
        assertNull(messageCaptor.getValue().getMediaId());
        verify(participantRepository).incrementUnreadForRecipients(10L, List.of(2L));

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<EventEnvelope> envelopeCaptor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outboxService).enqueueConversationEvent(eq(10L), envelopeCaptor.capture());
        verify(realtimeEventPublisher).publishAfterCommit(
                eq(envelopeCaptor.getValue()),
                eq(new LinkedHashSet<>(List.of(1L, 2L))));
        MessageCreatedPayload payload = (MessageCreatedPayload) envelopeCaptor.getValue().payload();
        assertEquals(List.of(2L), payload.recipientUserIds());
        assertEquals(100L, payload.messageId());
    }

    @Test
    void duplicateClientMessageIdReturnsOriginalWithoutWritingAgain() {
        User sender = user(1L, "sender@example.com");
        Conversion conversion = directConversion(10L, 1L, 2L);
        Message existing = message(100L, 10L, 1L, "client-1", "hello");
        MessageReceipt receipt = new MessageReceipt(100L, 2L);

        when(userRepository.findByEmailIgnoreCase(sender.getEmail())).thenReturn(Optional.of(sender));
        when(conversionRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(conversion));
        when(messageRepository.findByUserIdAndClientMessageId(1L, "client-1"))
                .thenReturn(Optional.of(existing));
        when(receiptRepository.findByMessageIdOrderByUserIdAsc(100L))
                .thenReturn(List.of(receipt));

        MessageResponseDto response = messageService.send(
                sender.getEmail(),
                10L,
                new SendMessageRequestDto("client-1", "hello", MessageType.TEXT, null));

        assertEquals(100L, response.id());
        verify(messageRepository, never()).saveAndFlush(any(Message.class));
        verify(outboxService, never()).enqueueConversationEvent(any(), any());
    }

    @Test
    void storesValidatedMediaIdOnNewMediaMessage() {
        User sender = user(1L, "sender@example.com");
        Conversion conversion = directConversion(10L, 1L, 2L);
        ConversationParticipant senderState = new ConversationParticipant(10L, 1L);
        ConversationParticipant recipientState = new ConversationParticipant(10L, 2L);
        String mediaId = "88c9857a-0f4c-4b34-824c-380f7d517f84";
        String mediaUrl = "/v1/api/media/" + mediaId;

        when(userRepository.findByEmailIgnoreCase(sender.getEmail())).thenReturn(Optional.of(sender));
        when(conversionRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(conversion));
        when(messageRepository.findByUserIdAndClientMessageId(1L, "media-1"))
                .thenReturn(Optional.empty());
        when(mediaStorageService.validateMessageReference(
                10L, MessageType.IMAGE, mediaUrl)).thenReturn(mediaId);
        when(participantRepository.findByConversionIdAndDeletedAtIsNullOrderByIdAsc(10L))
                .thenReturn(List.of(senderState, recipientState));
        when(userRepository.existsById(1L)).thenReturn(true);
        when(userRepository.existsById(2L)).thenReturn(true);
        when(messageRepository.saveAndFlush(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.setId(101L);
            message.setCreatedAt(LocalDateTime.of(2026, 10, 3, 12, 0));
            return message;
        });
        when(participantRepository.incrementUnreadForRecipients(10L, List.of(2L)))
                .thenReturn(1);

        MessageResponseDto response = messageService.send(
                sender.getEmail(),
                10L,
                new SendMessageRequestDto("media-1", mediaUrl, MessageType.IMAGE, null));

        assertEquals(101L, response.id());
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).saveAndFlush(messageCaptor.capture());
        assertEquals(mediaId, messageCaptor.getValue().getMediaId());
        assertEquals(sha256(mediaUrl), messageCaptor.getValue().getOriginalContentSha256());
    }

    @Test
    void originalReplayAfterEditReturnsCurrentRecordWithoutWritingOrPublishing() {
        User sender = user(1L, "sender@example.com");
        Conversion conversion = directConversion(10L, 1L, 2L);
        Message existing = message(100L, 10L, 1L, "client-1", "before");
        existing.setOriginalContentSha256(sha256("before"));
        existing.editContent("after", LocalDateTime.of(2026, 10, 3, 13, 0));

        when(userRepository.findByEmailIgnoreCase(sender.getEmail())).thenReturn(Optional.of(sender));
        when(conversionRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(conversion));
        when(messageRepository.findByUserIdAndClientMessageId(1L, "client-1"))
                .thenReturn(Optional.of(existing));
        when(receiptRepository.findByMessageIdOrderByUserIdAsc(100L)).thenReturn(List.of());

        MessageResponseDto response = messageService.send(
                sender.getEmail(),
                10L,
                new SendMessageRequestDto("client-1", "before", MessageType.TEXT, null));

        assertEquals("after", response.content());
        assertNotNull(response.editedAt());
        verify(messageRepository, never()).saveAndFlush(any(Message.class));
        verify(outboxService, never()).enqueueConversationEvent(any(), any());
        verify(realtimeEventPublisher, never()).publishAfterCommit(any(), any());
    }

    @Test
    void originalReplayAfterDeleteReturnsTombstoneWithoutWritingOrPublishing() {
        User sender = user(1L, "sender@example.com");
        Conversion conversion = directConversion(10L, 1L, 2L);
        Message existing = message(100L, 10L, 1L, "client-1", "before");
        existing.setOriginalContentSha256(sha256("before"));
        existing.softDelete(LocalDateTime.of(2026, 10, 3, 13, 0));

        when(userRepository.findByEmailIgnoreCase(sender.getEmail())).thenReturn(Optional.of(sender));
        when(conversionRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(conversion));
        when(messageRepository.findByUserIdAndClientMessageId(1L, "client-1"))
                .thenReturn(Optional.of(existing));
        when(receiptRepository.findByMessageIdOrderByUserIdAsc(100L)).thenReturn(List.of());

        MessageResponseDto response = messageService.send(
                sender.getEmail(),
                10L,
                new SendMessageRequestDto("client-1", "before", MessageType.TEXT, null));

        assertNull(response.content());
        assertNotNull(response.deletedAt());
        verify(messageRepository, never()).saveAndFlush(any(Message.class));
        verify(outboxService, never()).enqueueConversationEvent(any(), any());
        verify(realtimeEventPublisher, never()).publishAfterCommit(any(), any());
    }

    @Test
    void changedBodyWithReusedClientMessageIdIsRejected() {
        User sender = user(1L, "sender@example.com");
        Conversion conversion = directConversion(10L, 1L, 2L);
        Message existing = message(100L, 10L, 1L, "client-1", "before");
        existing.setOriginalContentSha256(sha256("before"));

        when(userRepository.findByEmailIgnoreCase(sender.getEmail())).thenReturn(Optional.of(sender));
        when(conversionRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(conversion));
        when(messageRepository.findByUserIdAndClientMessageId(1L, "client-1"))
                .thenReturn(Optional.of(existing));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> messageService.send(
                        sender.getEmail(),
                        10L,
                        new SendMessageRequestDto(
                                "client-1", "different", MessageType.TEXT, null)));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(messageRepository, never()).saveAndFlush(any(Message.class));
        verify(outboxService, never()).enqueueConversationEvent(any(), any());
    }

    @Test
    void mutatedLegacyMessageWithoutOriginalFingerprintRejectsReplay() {
        User sender = user(1L, "sender@example.com");
        Conversion conversion = directConversion(10L, 1L, 2L);
        Message existing = message(100L, 10L, 1L, "client-1", "before");
        existing.editContent("after", LocalDateTime.of(2026, 10, 3, 13, 0));

        when(userRepository.findByEmailIgnoreCase(sender.getEmail())).thenReturn(Optional.of(sender));
        when(conversionRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(conversion));
        when(messageRepository.findByUserIdAndClientMessageId(1L, "client-1"))
                .thenReturn(Optional.of(existing));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> messageService.send(
                        sender.getEmail(),
                        10L,
                        new SendMessageRequestDto(
                                "client-1", "before", MessageType.TEXT, null)));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(messageRepository, never()).saveAndFlush(any(Message.class));
        verify(outboxService, never()).enqueueConversationEvent(any(), any());
    }

    @Test
    void ownerEditsTextMessageAndPublishesMutationEvent() {
        User sender = user(1L, "sender@example.com");
        Conversion conversion = directConversion(10L, 1L, 2L);
        Message message = message(100L, 10L, 1L, "client-1", "before");
        message.setVersion(3L);

        when(userRepository.findByEmailIgnoreCase(sender.getEmail()))
                .thenReturn(Optional.of(sender));
        when(conversionRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(conversion));
        when(messageRepository.findForMutation(10L, 100L))
                .thenReturn(Optional.of(message));
        when(messageRepository.saveAndFlush(message)).thenAnswer(invocation -> {
            Message saved = invocation.getArgument(0);
            saved.setVersion(4L);
            return saved;
        });
        when(receiptRepository.findByMessageIdOrderByUserIdAsc(100L))
                .thenReturn(List.of());

        MessageResponseDto response = messageService.edit(
                sender.getEmail(),
                10L,
                100L,
                new EditMessageRequestDto("  after  "));

        assertEquals("after", response.content());
        assertNotNull(response.editedAt());
        assertNull(response.deletedAt());
        assertEquals(4L, response.version());
        verify(messageRepository).saveAndFlush(message);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<EventEnvelope> envelopeCaptor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outboxService).enqueueConversationEvent(eq(10L), envelopeCaptor.capture());
        EventEnvelope<?> envelope = envelopeCaptor.getValue();
        assertEquals(EventTypes.MESSAGE_UPDATED, envelope.eventType());
        MessageMutationPayload payload = (MessageMutationPayload) envelope.payload();
        assertEquals(100L, payload.messageId());
        assertEquals("after", payload.content());
        assertNotNull(payload.editedAt());
        assertNull(payload.deletedAt());
        assertEquals(4L, payload.version());
        verify(realtimeEventPublisher).publishAfterCommit(
                eq(envelope),
                eq(new LinkedHashSet<>(List.of(1L, 2L))));
    }

    @Test
    void nonOwnerCannotEditMessageAndNothingIsSaved() {
        User peer = user(2L, "peer@example.com");
        Conversion conversion = directConversion(10L, 1L, 2L);
        Message message = message(100L, 10L, 1L, "client-1", "before");

        when(userRepository.findByEmailIgnoreCase(peer.getEmail()))
                .thenReturn(Optional.of(peer));
        when(conversionRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(conversion));
        when(messageRepository.findForMutation(10L, 100L))
                .thenReturn(Optional.of(message));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> messageService.edit(
                        peer.getEmail(),
                        10L,
                        100L,
                        new EditMessageRequestDto("after")));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertEquals("Message not found", exception.getReason());
        verify(messageRepository, never()).saveAndFlush(any(Message.class));
        verify(outboxService, never()).enqueueConversationEvent(any(), any());
        verify(realtimeEventPublisher, never()).publishAfterCommit(any(), any());
    }

    @Test
    void ownerDeletesMessageAsTombstoneAndPublishesMutationEvent() {
        User sender = user(1L, "sender@example.com");
        Conversion conversion = directConversion(10L, 1L, 2L);
        Message message = message(100L, 10L, 1L, "client-1", "remove me");
        message.setVersion(5L);

        when(userRepository.findByEmailIgnoreCase(sender.getEmail()))
                .thenReturn(Optional.of(sender));
        when(conversionRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(conversion));
        when(messageRepository.findForMutation(10L, 100L))
                .thenReturn(Optional.of(message));
        when(messageRepository.saveAndFlush(message)).thenAnswer(invocation -> {
            Message saved = invocation.getArgument(0);
            saved.setVersion(6L);
            return saved;
        });
        when(receiptRepository.findByMessageIdOrderByUserIdAsc(100L))
                .thenReturn(List.of());

        MessageResponseDto response = messageService.delete(
                sender.getEmail(), 10L, 100L);

        assertNull(response.content());
        assertNotNull(response.deletedAt());
        assertEquals(6L, response.version());
        assertNull(message.getContent());
        assertNotNull(message.getDeletedAt());
        verify(messageRepository).saveAndFlush(message);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<EventEnvelope> envelopeCaptor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outboxService).enqueueConversationEvent(eq(10L), envelopeCaptor.capture());
        EventEnvelope<?> envelope = envelopeCaptor.getValue();
        assertEquals(EventTypes.MESSAGE_DELETED, envelope.eventType());
        MessageMutationPayload payload = (MessageMutationPayload) envelope.payload();
        assertEquals(100L, payload.messageId());
        assertNull(payload.content());
        assertNotNull(payload.deletedAt());
        assertEquals(6L, payload.version());
        verify(realtimeEventPublisher).publishAfterCommit(
                eq(envelope),
                eq(new LinkedHashSet<>(List.of(1L, 2L))));
    }

    @Test
    void repeatedDeleteIsIdempotentAndDoesNotPublishSecondEvent() {
        User sender = user(1L, "sender@example.com");
        Conversion conversion = directConversion(10L, 1L, 2L);
        Message message = message(100L, 10L, 1L, "client-1", "remove me");

        when(userRepository.findByEmailIgnoreCase(sender.getEmail()))
                .thenReturn(Optional.of(sender));
        when(conversionRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(conversion));
        when(messageRepository.findForMutation(10L, 100L))
                .thenReturn(Optional.of(message));
        when(messageRepository.saveAndFlush(message)).thenReturn(message);
        when(receiptRepository.findByMessageIdOrderByUserIdAsc(100L))
                .thenReturn(List.of());

        MessageResponseDto firstResponse = messageService.delete(
                sender.getEmail(), 10L, 100L);
        LocalDateTime firstDeletedAt = firstResponse.deletedAt();
        clearInvocations(
                messageRepository,
                outboxService,
                realtimeEventPublisher);

        MessageResponseDto repeatedResponse = messageService.delete(
                sender.getEmail(), 10L, 100L);

        assertEquals(firstDeletedAt, repeatedResponse.deletedAt());
        assertNull(repeatedResponse.content());
        verify(messageRepository, never()).saveAndFlush(any(Message.class));
        verify(outboxService, never()).enqueueConversationEvent(any(), any());
        verify(realtimeEventPublisher, never()).publishAfterCommit(any(), any());
    }

    @Test
    void rejectsEditWhenConversationIsSoftDeleted() {
        User sender = user(1L, "sender@example.com");
        Conversion conversion = directConversion(10L, 1L, 2L);
        conversion.setDeletedAt(LocalDateTime.of(2026, 10, 3, 13, 0));

        when(userRepository.findByEmailIgnoreCase(sender.getEmail()))
                .thenReturn(Optional.of(sender));
        when(conversionRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(conversion));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> messageService.edit(
                        sender.getEmail(),
                        10L,
                        100L,
                        new EditMessageRequestDto("after")));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertEquals("Conversion not found", exception.getReason());
        verify(messageRepository, never()).findForMutation(any(), any());
        verify(messageRepository, never()).saveAndFlush(any(Message.class));
        verify(outboxService, never()).enqueueConversationEvent(any(), any());
    }

    @Test
    void rejectsDeleteWhenConversationIsSoftDeleted() {
        User sender = user(1L, "sender@example.com");
        Conversion conversion = directConversion(10L, 1L, 2L);
        conversion.setDeletedAt(LocalDateTime.of(2026, 10, 3, 13, 0));

        when(userRepository.findByEmailIgnoreCase(sender.getEmail()))
                .thenReturn(Optional.of(sender));
        when(conversionRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(conversion));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> messageService.delete(sender.getEmail(), 10L, 100L));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertEquals("Conversion not found", exception.getReason());
        verify(messageRepository, never()).findForMutation(any(), any());
        verify(messageRepository, never()).saveAndFlush(any(Message.class));
        verify(outboxService, never()).enqueueConversationEvent(any(), any());
    }

    @Test
    void readThroughUpdatesReceiptWatermarkAndPublishesStateEvent() {
        User recipient = user(2L, "recipient@example.com");
        Conversion conversion = directConversion(10L, 1L, 2L);
        Message boundary = message(100L, 10L, 1L, "client-1", "hello");
        ConversationParticipant participant = new ConversationParticipant(10L, 2L);
        participant.setUnreadCount(1);

        when(userRepository.findByEmailIgnoreCase(recipient.getEmail()))
                .thenReturn(Optional.of(recipient));
        when(conversionRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(conversion));
        when(messageRepository.findByIdAndConversionId(100L, 10L))
                .thenReturn(Optional.of(boundary));
        when(participantRepository.findActiveForUpdate(10L, 2L))
                .thenReturn(Optional.of(participant));
        when(receiptRepository.markReadThrough(eq(10L), eq(2L), eq(100L), any()))
                .thenReturn(1);
        when(receiptRepository.countUnread(10L, 2L)).thenReturn(0L);

        MessageStateResponseDto response = messageService.markRead(
                recipient.getEmail(), 10L, 100L);

        assertEquals(MessageStatus.READ, response.status());
        assertEquals(1, response.updatedMessages());
        assertEquals(0, response.unreadCount());
        assertEquals(100L, participant.getLastReadMessageId());
        assertEquals(0, participant.getUnreadCount());
        verify(messageRepository).recomputeAggregateStatusesThrough(
                eq(10L), eq(100L), any());
        verify(outboxService).enqueueConversationEvent(eq(10L), any());
    }

    @Test
    void rejectsUserOutsideDirectConversationWithoutLeakingItsExistence() {
        User stranger = user(3L, "stranger@example.com");
        Conversion conversion = directConversion(10L, 1L, 2L);

        when(userRepository.findByEmailIgnoreCase(stranger.getEmail()))
                .thenReturn(Optional.of(stranger));
        when(conversionRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(conversion));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> messageService.send(
                        stranger.getEmail(),
                        10L,
                        new SendMessageRequestDto(
                                "client-unauthorized", "hello", MessageType.TEXT, null)));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertTrue(exception.getReason().contains("Conversion"));
        verify(messageRepository, never()).saveAndFlush(any(Message.class));
    }

    @Test
    void rejectsRemoteMediaReferenceBeforePersistingMessage() {
        User sender = user(1L, "sender@example.com");
        Conversion conversion = directConversion(10L, 1L, 2L);
        when(userRepository.findByEmailIgnoreCase(sender.getEmail())).thenReturn(Optional.of(sender));
        when(conversionRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(conversion));
        when(messageRepository.findByUserIdAndClientMessageId(1L, "media-1"))
                .thenReturn(Optional.empty());
        doThrow(new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Media messages must reference a local uploaded media URL"))
                .when(mediaStorageService)
                .validateMessageReference(10L, MessageType.IMAGE, "https://example.com/image.jpg");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> messageService.send(
                        sender.getEmail(),
                        10L,
                        new SendMessageRequestDto(
                                "media-1",
                                "https://example.com/image.jpg",
                                MessageType.IMAGE,
                                null)));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(messageRepository, never()).saveAndFlush(any(Message.class));
        verify(outboxService, never()).enqueueConversationEvent(any(), any());
    }

    private User user(Long id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        return user;
    }

    private Conversion directConversion(Long id, Long ownerId, Long peerId) {
        Conversion conversion = new Conversion();
        conversion.setId(id);
        conversion.setUserId(ownerId);
        conversion.setClientId(peerId);
        conversion.setConversionType(ConversionType.INDIVIDUAL);
        return conversion;
    }

    private Message message(
            Long id,
            Long conversionId,
            Long senderId,
            String clientMessageId,
            String content) {
        Message message = new Message();
        message.setId(id);
        message.setConversionId(conversionId);
        message.setUserId(senderId);
        message.setClientMessageId(clientMessageId);
        message.setContent(content);
        message.setMessageType(MessageType.TEXT);
        message.setStatus(MessageStatus.SENT);
        message.setCreatedAt(LocalDateTime.of(2026, 10, 3, 12, 0));
        return message;
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
