package com.rohitsamota.my_messenger.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record NotificationPayload(
        Long recipientUserId,
        Long conversionId,
        Long messageId,
        Long senderUserId,
        String title,
        String body,
        Map<String, String> data,
        Instant createdAt) {

    public NotificationPayload {
        requirePositive(recipientUserId, "recipientUserId");
        requirePositive(conversionId, "conversionId");
        requirePositive(messageId, "messageId");
        requirePositive(senderUserId, "senderUserId");
        title = requireText(title, "title");
        body = body == null ? "" : body;
        data = data == null ? Map.of() : Map.copyOf(data);
        Objects.requireNonNull(createdAt, "createdAt is required");
    }

    private static void requirePositive(Long value, String fieldName) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }
}
