package com.rohitsamota.my_messenger.services;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.rohitsamota.my_messenger.entity.User;
import com.rohitsamota.my_messenger.event.EventEnvelope;
import com.rohitsamota.my_messenger.event.EventTypes;
import com.rohitsamota.my_messenger.event.MessageStateChangedPayload;
import com.rohitsamota.my_messenger.enums.MessageStatus;
import com.rohitsamota.my_messenger.repo.UserInfoRepository;

@ExtendWith(MockitoExtension.class)
class RealtimeEventPublisherTests {
    @Mock
    private UserInfoRepository userRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private RealtimeEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new RealtimeEventPublisher(userRepository, messagingTemplate);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deliversToEachUserOnlyAfterCommit() {
        User first = user(1L, "first@example.com");
        User second = user(2L, "second@example.com");
        when(userRepository.findAllById(any())).thenReturn(List.of(first, second));
        EventEnvelope<MessageStateChangedPayload> event = EventEnvelope.v1(
                EventTypes.MESSAGE_STATE_CHANGED,
                "CONVERSATION",
                "10",
                "receipt-10",
                new MessageStateChangedPayload(
                        10L, 2L, 100L, MessageStatus.READ, Instant.now()));

        publisher.publishAfterCommit(event, List.of(1L, 2L));

        verify(messagingTemplate, never()).convertAndSendToUser(
                "first@example.com", RealtimeEventPublisher.USER_EVENT_DESTINATION, event);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCommit());
        verify(messagingTemplate).convertAndSendToUser(
                "first@example.com", RealtimeEventPublisher.USER_EVENT_DESTINATION, event);
        verify(messagingTemplate).convertAndSendToUser(
                "second@example.com", RealtimeEventPublisher.USER_EVENT_DESTINATION, event);
    }

    private User user(Long id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        return user;
    }
}
