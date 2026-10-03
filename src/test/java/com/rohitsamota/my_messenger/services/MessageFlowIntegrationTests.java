package com.rohitsamota.my_messenger.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.rohitsamota.my_messenger.dto.MessageResponseDto;
import com.rohitsamota.my_messenger.dto.MessageStateResponseDto;
import com.rohitsamota.my_messenger.dto.SendMessageRequestDto;
import com.rohitsamota.my_messenger.entity.ConversationParticipant;
import com.rohitsamota.my_messenger.entity.Conversion;
import com.rohitsamota.my_messenger.entity.GroupMembers;
import com.rohitsamota.my_messenger.entity.Groups;
import com.rohitsamota.my_messenger.entity.Message;
import com.rohitsamota.my_messenger.entity.MessageReceipt;
import com.rohitsamota.my_messenger.entity.OutboxEvent;
import com.rohitsamota.my_messenger.entity.User;
import com.rohitsamota.my_messenger.enums.ConversionType;
import com.rohitsamota.my_messenger.enums.GroupUserRole;
import com.rohitsamota.my_messenger.enums.MessageStatus;
import com.rohitsamota.my_messenger.enums.MessageType;
import com.rohitsamota.my_messenger.enums.Status;
import com.rohitsamota.my_messenger.enums.UserRole;
import com.rohitsamota.my_messenger.event.EventTypes;
import com.rohitsamota.my_messenger.messaging.ConversationEventConsumer;
import com.rohitsamota.my_messenger.messaging.KafkaTopics;
import com.rohitsamota.my_messenger.messaging.NotificationEventConsumer;
import com.rohitsamota.my_messenger.repo.ConversationParticipantRepository;
import com.rohitsamota.my_messenger.repo.ConversionRepoI;
import com.rohitsamota.my_messenger.repo.MessageReceiptRepository;
import com.rohitsamota.my_messenger.repo.MessageRepoI;
import com.rohitsamota.my_messenger.repo.NotificationRepository;
import com.rohitsamota.my_messenger.repo.OutboxEventRepository;
import com.rohitsamota.my_messenger.repo.UserInfoRepository;

import jakarta.persistence.EntityManager;

@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "app.kafka.topics.create=false",
        "app.outbox.relay-enabled=false"
})
@Transactional
class MessageFlowIntegrationTests {
    @Autowired
    private MessageService messageService;

    @Autowired
    private ConversionService conversionService;

    @Autowired
    private UserInfoRepository userRepository;

    @Autowired
    private ConversionRepoI conversionRepository;

    @Autowired
    private ConversationParticipantRepository participantRepository;

    @Autowired
    private MessageRepoI messageRepository;

    @Autowired
    private MessageReceiptRepository receiptRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ConversationEventConsumer conversationEventConsumer;

    @Autowired
    private NotificationEventConsumer notificationEventConsumer;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    void directMessageMovesFromSentToDeliveredToReadAndClearsUnread() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User sender = userRepository.saveAndFlush(user("sender-" + suffix + "@example.com"));
        User recipient = userRepository.saveAndFlush(user("recipient-" + suffix + "@example.com"));

        Conversion conversion = new Conversion();
        conversion.setUserId(sender.getId());
        conversion.setClientId(recipient.getId());
        conversion.setConversionType(ConversionType.INDIVIDUAL);
        conversion = conversionRepository.saveAndFlush(conversion);
        Long conversionId = conversion.getId();

        MessageResponseDto sent = messageService.send(
                sender.getEmail(),
                conversionId,
                new SendMessageRequestDto(
                        "integration-" + suffix,
                        "hello",
                        MessageType.TEXT,
                        null));
        MessageResponseDto replay = messageService.send(
                sender.getEmail(),
                conversion.getId(),
                new SendMessageRequestDto(
                        "integration-" + suffix,
                        "hello",
                        MessageType.TEXT,
                        null));
        assertEquals(sent.id(), replay.id());

        flushAndClear();
        ConversationParticipant recipientState = participantRepository
                .findByConversionIdAndUserId(conversion.getId(), recipient.getId())
                .orElseThrow();
        assertEquals(1, recipientState.getUnreadCount());
        var inbox = conversionService.listForUser(recipient.getEmail(), null, false, 20);
        assertEquals(1, inbox.items().size());
        assertEquals(conversion.getId(), inbox.items().get(0).id());
        assertEquals(1, inbox.items().get(0).unreadCount());
        MessageReceipt receipt = receiptRepository
                .findByMessageIdAndUserId(sent.id(), recipient.getId())
                .orElseThrow();
        assertEquals(MessageStatus.SENT, receipt.getStatus());

        MessageStateResponseDto delivered = messageService.acknowledgeDelivered(
                recipient.getEmail(), conversion.getId(), sent.id());
        assertEquals(1, delivered.updatedMessages());
        flushAndClear();
        assertEquals(
                MessageStatus.DELIVERED,
                receiptRepository.findByMessageIdAndUserId(sent.id(), recipient.getId())
                        .orElseThrow().getStatus());
        assertEquals(
                MessageStatus.DELIVERED,
                messageRepository.findById(sent.id()).orElseThrow().getStatus());

        MessageStateResponseDto read = messageService.markRead(
                recipient.getEmail(), conversion.getId(), sent.id());
        assertEquals(1, read.updatedMessages());
        assertEquals(0, read.unreadCount());
        flushAndClear();

        Message persistedMessage = messageRepository.findById(sent.id()).orElseThrow();
        MessageReceipt persistedReceipt = receiptRepository
                .findByMessageIdAndUserId(sent.id(), recipient.getId())
                .orElseThrow();
        ConversationParticipant persistedState = participantRepository
                .findByConversionIdAndUserId(conversion.getId(), recipient.getId())
                .orElseThrow();
        assertEquals(MessageStatus.READ, persistedMessage.getStatus());
        assertEquals(MessageStatus.READ, persistedReceipt.getStatus());
        assertEquals(0, persistedState.getUnreadCount());
        assertEquals(sent.id(), persistedState.getLastReadMessageId());
    }

    @Test
    void groupMessageSnapshotsActiveRecipientsAndAggregatesTheirIndependentStates() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User sender = userRepository.saveAndFlush(user("group-sender-" + suffix + "@example.com"));
        User reader = userRepository.saveAndFlush(user("group-reader-" + suffix + "@example.com"));
        User secondRecipient = userRepository.saveAndFlush(
                user("group-second-" + suffix + "@example.com"));
        User removedMember = userRepository.saveAndFlush(
                user("group-removed-" + suffix + "@example.com"));

        Groups group = new Groups("test group", "integration test");
        group.setMemberCount(3);
        entityManager.persist(group);
        entityManager.flush();
        entityManager.persist(new GroupMembers(group.getId(), sender.getId(), GroupUserRole.ADMIN));
        entityManager.persist(new GroupMembers(group.getId(), reader.getId(), GroupUserRole.MEMBER));
        entityManager.persist(new GroupMembers(
                group.getId(), secondRecipient.getId(), GroupUserRole.MEMBER));
        GroupMembers removed = new GroupMembers(
                group.getId(), removedMember.getId(), GroupUserRole.MEMBER);
        removed.leave(null);
        entityManager.persist(removed);

        Conversion conversion = new Conversion();
        conversion.setUserId(sender.getId());
        conversion.setClientId(group.getId());
        conversion.setConversionType(ConversionType.GROUP);
        conversion = conversionRepository.saveAndFlush(conversion);

        MessageResponseDto sent = messageService.send(
                sender.getEmail(),
                conversion.getId(),
                new SendMessageRequestDto(
                        "group-integration-" + suffix,
                        "hello group",
                        MessageType.TEXT,
                        null));
        flushAndClear();

        List<MessageReceipt> receipts = receiptRepository
                .findByMessageIdOrderByUserIdAsc(sent.id());
        assertEquals(2, receipts.size());
        assertEquals(
                List.of(reader.getId(), secondRecipient.getId()).stream().sorted().toList(),
                receipts.stream().map(MessageReceipt::getUserId).sorted().toList());
        assertFalse(receipts.stream().anyMatch(receipt ->
                receipt.getUserId().equals(removedMember.getId())));

        messageService.markRead(reader.getEmail(), conversion.getId(), sent.id());
        flushAndClear();
        assertEquals(MessageStatus.SENT,
                messageRepository.findById(sent.id()).orElseThrow().getStatus());

        messageService.acknowledgeDelivered(
                secondRecipient.getEmail(), conversion.getId(), sent.id());
        flushAndClear();
        assertEquals(MessageStatus.DELIVERED,
                messageRepository.findById(sent.id()).orElseThrow().getStatus());

        messageService.markRead(secondRecipient.getEmail(), conversion.getId(), sent.id());
        flushAndClear();
        assertEquals(MessageStatus.READ,
                messageRepository.findById(sent.id()).orElseThrow().getStatus());
        assertEquals(0, participantRepository
                .findByConversionIdAndUserId(conversion.getId(), reader.getId())
                .orElseThrow().getUnreadCount());
        assertEquals(0, participantRepository
                .findByConversionIdAndUserId(conversion.getId(), secondRecipient.getId())
                .orElseThrow().getUnreadCount());
    }

    @Test
    void replayedKafkaEventsCreateOneDurableNotification() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User sender = userRepository.saveAndFlush(user("event-sender-" + suffix + "@example.com"));
        User recipient = userRepository.saveAndFlush(user("event-recipient-" + suffix + "@example.com"));
        Conversion conversion = new Conversion();
        conversion.setUserId(sender.getId());
        conversion.setClientId(recipient.getId());
        conversion.setConversionType(ConversionType.INDIVIDUAL);
        conversion = conversionRepository.saveAndFlush(conversion);
        Long eventConversionId = conversion.getId();

        MessageResponseDto sent = messageService.send(
                sender.getEmail(),
                eventConversionId,
                new SendMessageRequestDto(
                        "event-integration-" + suffix,
                        "notify me",
                        MessageType.TEXT,
                        null));
        entityManager.flush();

        OutboxEvent conversationEvent = outboxEventRepository.findAll().stream()
                .filter(event -> EventTypes.MESSAGE_CREATED.equals(event.getEventType()))
                .filter(event -> eventConversionId.toString().equals(event.getAggregateId()))
                .filter(event -> event.getPayload().contains("event-integration-" + suffix))
                .findFirst()
                .orElseThrow();
        conversationEventConsumer.receive(conversationEvent.getPayload());
        conversationEventConsumer.receive(conversationEvent.getPayload());
        entityManager.flush();

        List<OutboxEvent> notificationEvents = outboxEventRepository.findAll().stream()
                .filter(event -> KafkaTopics.NOTIFICATION_EVENTS.equals(event.getTopic()))
                .filter(event -> (recipient.getId() + ":" + sent.id()).equals(event.getAggregateId()))
                .toList();
        assertEquals(1, notificationEvents.size());

        OutboxEvent notificationEvent = notificationEvents.get(0);
        notificationEventConsumer.receive(notificationEvent.getPayload());
        notificationEventConsumer.receive(notificationEvent.getPayload());
        entityManager.flush();

        assertEquals(1, notificationRepository.findByUserIdOrderByIdDesc(
                recipient.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).stream()
                .filter(notification -> notification.getMessageId().equals(sent.id()))
                .count());
    }

    private User user(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPassword("not-used-in-this-test");
        user.setStatus(Status.ACTIVE);
        user.setUserRole(UserRole.USER);
        return user;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
