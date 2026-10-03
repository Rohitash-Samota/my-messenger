package com.rohitsamota.my_messenger.dto;

import com.rohitsamota.my_messenger.enums.MessageStatus;

public record MessageStateResponseDto(
        Long conversionId,
        Long upToMessageId,
        MessageStatus status,
        int updatedMessages,
        long unreadCount) {
}
