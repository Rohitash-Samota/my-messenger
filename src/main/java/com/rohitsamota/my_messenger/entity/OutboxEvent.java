package com.rohitsamota.my_messenger.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.rohitsamota.my_messenger.messaging.outbox.OutboxStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    @Column(name = "topic", nullable = false, length = 200)
    private String topic;

    @Column(name = "message_key", nullable = false, length = 200)
    private String messageKey;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    @Lob
    @Column(name = "payload", nullable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "lock_owner", length = 160)
    private String lockOwner;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
    }

    private OutboxEvent(
            UUID eventId,
            String topic,
            String messageKey,
            String eventType,
            String aggregateType,
            String aggregateId,
            String payload,
            Instant now) {
        this.eventId = Objects.requireNonNull(eventId, "eventId is required").toString();
        this.topic = requireText(topic, "topic");
        this.messageKey = requireText(messageKey, "messageKey");
        this.eventType = requireText(eventType, "eventType");
        this.aggregateType = requireText(aggregateType, "aggregateType");
        this.aggregateId = requireText(aggregateId, "aggregateId");
        this.payload = requireText(payload, "payload");
        this.status = OutboxStatus.PENDING;
        this.attemptCount = 0;
        this.availableAt = Objects.requireNonNull(now, "now is required");
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static OutboxEvent pending(
            UUID eventId,
            String topic,
            String messageKey,
            String eventType,
            String aggregateType,
            String aggregateId,
            String payload,
            Instant now) {
        return new OutboxEvent(
                eventId,
                topic,
                messageKey,
                eventType,
                aggregateType,
                aggregateId,
                payload,
                now);
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return UUID.fromString(eventId);
    }

    public String getEventIdValue() {
        return eventId;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getEventType() {
        return eventType;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public String getLockOwner() {
        return lockOwner;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }
}
