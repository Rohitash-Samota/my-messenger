package com.rohitsamota.my_messenger.messaging;

import org.springframework.stereotype.Component;

import com.rohitsamota.my_messenger.event.EventEnvelope;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class EventEnvelopeReader {
    private final ObjectMapper objectMapper;

    public EventEnvelopeReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String eventType(String value) {
        JsonNode root = objectMapper.readTree(value);
        JsonNode eventType = root.get("eventType");
        if (eventType == null || eventType.isNull() || eventType.asText().isBlank()) {
            throw new IllegalArgumentException("Kafka eventType is required");
        }
        return eventType.asText();
    }

    public <T> EventEnvelope<T> read(String value, TypeReference<EventEnvelope<T>> type) {
        EventEnvelope<T> envelope = objectMapper.readValue(value, type);
        if (envelope.schemaVersion() != 1) {
            throw new IllegalArgumentException(
                    "Unsupported event schema version: " + envelope.schemaVersion());
        }
        return envelope;
    }
}
