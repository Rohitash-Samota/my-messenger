package com.rohitsamota.my_messenger.event;

import java.time.Instant;
import java.util.Objects;

import com.rohitsamota.my_messenger.enums.MessageStatus;

public record MessageStateChangedPayload(
        Long conversionId,
        Long recipientUserId,
        Long upToMessageId,
        MessageStatus state,
        Instant changedAt) {

    public MessageStateChangedPayload {
        requirePositive(conversionId, "conversionId");
        requirePositive(recipientUserId, "recipientUserId");
        requirePositive(upToMessageId, "upToMessageId");
        Objects.requireNonNull(state, "state is required");
        Objects.requireNonNull(changedAt, "changedAt is required");
        if (state != MessageStatus.DELIVERED && state != MessageStatus.READ) {
            throw new IllegalArgumentException("state must be DELIVERED or READ");
        }
    }

    private static void requirePositive(Long value, String fieldName) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
