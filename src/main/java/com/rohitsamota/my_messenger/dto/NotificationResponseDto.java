package com.rohitsamota.my_messenger.dto;

import java.time.LocalDateTime;

public record NotificationResponseDto(
        Long id,
        Long conversionId,
        Long messageId,
        String title,
        String body,
        LocalDateTime readAt,
        LocalDateTime createdAt) {
}
