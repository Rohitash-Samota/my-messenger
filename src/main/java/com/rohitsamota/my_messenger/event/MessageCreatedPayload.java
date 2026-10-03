package com.rohitsamota.my_messenger.event;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import com.rohitsamota.my_messenger.enums.MessageType;

public record MessageCreatedPayload(
        Long messageId,
        Long conversionId,
        Long senderUserId,
        List<Long> recipientUserIds,
        Long parentMessageId,
        MessageType messageType,
        String content,
        Instant createdAt) {

    public MessageCreatedPayload {
        requirePositive(messageId, "messageId");
        requirePositive(conversionId, "conversionId");
        requirePositive(senderUserId, "senderUserId");
        if (recipientUserIds == null || recipientUserIds.isEmpty()) {
            throw new IllegalArgumentException("recipientUserIds must not be empty");
        }
        recipientUserIds = List.copyOf(recipientUserIds);
        recipientUserIds.forEach(recipientId -> requirePositive(recipientId, "recipientUserId"));
        if (new HashSet<>(recipientUserIds).size() != recipientUserIds.size()) {
            throw new IllegalArgumentException("recipientUserIds must not contain duplicates");
        }
        if (recipientUserIds.contains(senderUserId)) {
            throw new IllegalArgumentException("recipientUserIds must not contain senderUserId");
        }
        if (parentMessageId != null) {
            requirePositive(parentMessageId, "parentMessageId");
        }
        Objects.requireNonNull(messageType, "messageType is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
    }

    private static void requirePositive(Long value, String fieldName) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
