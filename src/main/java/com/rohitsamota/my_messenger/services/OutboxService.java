package com.rohitsamota.my_messenger.services;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.rohitsamota.my_messenger.entity.OutboxEvent;
import com.rohitsamota.my_messenger.event.EventEnvelope;
import com.rohitsamota.my_messenger.messaging.KafkaTopics;
import com.rohitsamota.my_messenger.repo.OutboxEventRepository;

import tools.jackson.databind.ObjectMapper;

@Service
public class OutboxService {
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Persists an event in the caller's domain transaction. The caller must be
     * transactional so a domain write can never commit without its event.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(String topic, String messageKey, EventEnvelope<?> envelope) {
        Objects.requireNonNull(envelope, "envelope is required");
        if (!KafkaTopics.isPublishable(topic)) {
            throw new IllegalArgumentException("Unsupported outbox topic: " + topic);
        }
        if (messageKey == null || messageKey.isBlank()) {
            throw new IllegalArgumentException("messageKey is required");
        }

        String payload = objectMapper.writeValueAsString(envelope);
        OutboxEvent outboxEvent = OutboxEvent.pending(
                envelope.eventId(),
                topic,
                messageKey,
                envelope.eventType(),
                envelope.aggregateType(),
                envelope.aggregateId(),
                payload,
                Instant.now());
        outboxEventRepository.save(outboxEvent);
        return envelope.eventId();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueueConversationEvent(Long conversionId, EventEnvelope<?> envelope) {
        requirePositive(conversionId, "conversionId");
        assertAggregateId(conversionId.toString(), envelope);
        return enqueue(KafkaTopics.CONVERSATION_EVENTS, conversionId.toString(), envelope);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueueNotificationEvent(Long recipientUserId, EventEnvelope<?> envelope) {
        requirePositive(recipientUserId, "recipientUserId");
        return enqueue(KafkaTopics.NOTIFICATION_EVENTS, recipientUserId.toString(), envelope);
    }

    private void assertAggregateId(String expectedAggregateId, EventEnvelope<?> envelope) {
        Objects.requireNonNull(envelope, "envelope is required");
        if (!expectedAggregateId.equals(envelope.aggregateId())) {
            throw new IllegalArgumentException("Envelope aggregateId does not match the conversion id");
        }
    }

    private void requirePositive(Long value, String fieldName) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
