package com.rohitsamota.my_messenger.services;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.rohitsamota.my_messenger.event.EventEnvelope;
import com.rohitsamota.my_messenger.repo.UserInfoRepository;

@Service
public class RealtimeEventPublisher {
    public static final String USER_EVENT_DESTINATION = "/queue/events";
    private static final Logger log = LoggerFactory.getLogger(RealtimeEventPublisher.class);

    private final UserInfoRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public RealtimeEventPublisher(
            UserInfoRepository userRepository,
            SimpMessagingTemplate messagingTemplate) {
        this.userRepository = userRepository;
        this.messagingTemplate = messagingTemplate;
    }

    public void publishAfterCommit(EventEnvelope<?> event, Collection<Long> recipientUserIds) {
        if (event == null) {
            throw new IllegalArgumentException("event is required");
        }
        if (recipientUserIds == null || recipientUserIds.isEmpty()) {
            throw new IllegalArgumentException("recipientUserIds must not be empty");
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Realtime events must be scheduled inside a transaction");
        }

        LinkedHashSet<Long> uniqueIds = new LinkedHashSet<>(recipientUserIds);
        List<String> recipientEmails = userRepository.findAllById(uniqueIds).stream()
                .filter(user -> uniqueIds.contains(user.getId()))
                .map(user -> user.getEmail())
                .distinct()
                .toList();
        if (recipientEmails.size() != uniqueIds.size()) {
            throw new IllegalStateException("A realtime event recipient no longer exists");
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (String recipientEmail : recipientEmails) {
                    try {
                        messagingTemplate.convertAndSendToUser(
                                recipientEmail,
                                USER_EVENT_DESTINATION,
                                event);
                    } catch (RuntimeException exception) {
                        log.warn(
                                "Realtime delivery failed for event {} and user {}",
                                event.eventId(),
                                recipientEmail,
                                exception);
                    }
                }
            }
        });
    }
}
