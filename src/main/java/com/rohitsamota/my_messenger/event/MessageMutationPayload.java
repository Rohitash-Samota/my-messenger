package com.rohitsamota.my_messenger.event;

import java.time.LocalDateTime;
import java.util.Objects;

import com.rohitsamota.my_messenger.enums.MessageStatus;
import com.rohitsamota.my_messenger.enums.MessageType;

public record MessageMutationPayload(
        Long messageId,
        Long conversionId,
        Long senderUserId,
        Long parentMessageId,
        String clientMessageId,
        String content,
        MessageType messageType,
        MessageStatus status,
        LocalDateTime createdAt,
        LocalDateTime editedAt,
        LocalDateTime deletedAt,
        long version) {

    public MessageMutationPayload {
        requirePositive(messageId, "messageId");
        requirePositive(conversionId, "conversionId");
        requirePositive(senderUserId, "senderUserId");
        if (parentMessageId != null) requirePositive(parentMessageId, "parentMessageId");
        if (clientMessageId == null || clientMessageId.isBlank()) {
            throw new IllegalArgumentException("clientMessageId is required");
        }
        Objects.requireNonNull(messageType, "messageType is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
        if (deletedAt != null) content = null;
    }

    private static void requirePositive(Long value, String fieldName) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
