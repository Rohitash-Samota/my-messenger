package com.rohitsamota.my_messenger.dto;

import java.time.LocalDateTime;

import com.rohitsamota.my_messenger.enums.ConversionType;

public record ConversionResponseDto(
        Long id,
        Long clientId,
        ConversionType conversionType,
        boolean pinned,
        boolean archived,
        long unreadCount,
        Long lastMessageId,
        LocalDateTime lastActivityAt) {
}
