package com.rohitsamota.my_messenger.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.rohitsamota.my_messenger.enums.MessageStatus;
import com.rohitsamota.my_messenger.enums.MessageType;

public record MessageResponseDto(
        Long id,
        Long conversionId,
        Long senderUserId,
        Long parentMessageId,
        String clientMessageId,
        String content,
        MessageType messageType,
        MessageStatus status,
        LocalDateTime createdAt,
        List<MessageReceiptResponseDto> receipts) {
}
