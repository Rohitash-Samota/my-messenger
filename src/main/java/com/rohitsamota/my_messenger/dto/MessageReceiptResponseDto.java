package com.rohitsamota.my_messenger.dto;

import java.time.LocalDateTime;

import com.rohitsamota.my_messenger.enums.MessageStatus;

public record MessageReceiptResponseDto(
        Long userId,
        MessageStatus status,
        LocalDateTime deliveredAt,
        LocalDateTime readAt) {
}
