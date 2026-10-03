package com.rohitsamota.my_messenger.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EventEnvelope<T>(
        UUID eventId,
        int schemaVersion,
        String eventType,
        Instant occurredAt,
        String aggregateType,
        String aggregateId,
        String correlationId,
        T payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId is required");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        eventType = requireText(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        aggregateType = requireText(aggregateType, "aggregateType");
        aggregateId = requireText(aggregateId, "aggregateId");
        correlationId = requireText(correlationId, "correlationId");
        Objects.requireNonNull(payload, "payload is required");
    }

    public static <T> EventEnvelope<T> v1(
            String eventType,
            String aggregateType,
            String aggregateId,
            String correlationId,
            T payload) {
        UUID eventId = UUID.randomUUID();
        String resolvedCorrelationId = correlationId == null || correlationId.isBlank()
                ? eventId.toString()
                : correlationId;
        return new EventEnvelope<>(
                eventId,
                1,
                eventType,
                Instant.now(),
                aggregateType,
                aggregateId,
                resolvedCorrelationId,
                payload);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }
}
