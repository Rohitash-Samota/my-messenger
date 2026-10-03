package com.rohitsamota.my_messenger.dto;

import java.time.Instant;

import com.rohitsamota.my_messenger.enums.MessageType;

public record MediaUploadResponseDto(
        String id,
        Long conversationId,
        MessageType messageType,
        String url,
        String originalFilename,
        String contentType,
        long size,
        Instant createdAt) {
}
