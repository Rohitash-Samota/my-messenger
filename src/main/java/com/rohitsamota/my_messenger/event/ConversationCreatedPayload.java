package com.rohitsamota.my_messenger.event;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record ConversationCreatedPayload(
        Long conversationId,
        Long creatorUserId,
        List<Long> participantUserIds,
        Instant createdAt) {

    public ConversationCreatedPayload {
        requirePositive(conversationId, "conversationId");
        requirePositive(creatorUserId, "creatorUserId");
        if (participantUserIds == null || participantUserIds.size() != 2) {
            throw new IllegalArgumentException("Direct conversation must have exactly two participants");
        }
        participantUserIds = List.copyOf(participantUserIds);
        participantUserIds.forEach(id -> requirePositive(id, "participantUserId"));
        if (new HashSet<>(participantUserIds).size() != participantUserIds.size()
                || !participantUserIds.contains(creatorUserId)) {
            throw new IllegalArgumentException("Direct conversation participants are invalid");
        }
        Objects.requireNonNull(createdAt, "createdAt is required");
    }

    private static void requirePositive(Long value, String fieldName) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
