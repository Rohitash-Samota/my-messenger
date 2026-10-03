package com.rohitsamota.my_messenger.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
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
import com.rohitsamota.my_messenger.event.MessageCreatedPayload;
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
                outboxService);
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
        verify(participantRepository).incrementUnreadForRecipients(10L, List.of(2L));

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<EventEnvelope> envelopeCaptor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outboxService).enqueueConversationEvent(eq(10L), envelopeCaptor.capture());
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
}
