package com.rohitsamota.my_messenger.dto;

import java.util.List;

public record NotificationCursorResponseDto(
        List<NotificationResponseDto> items,
        Long nextCursor,
        boolean hasMore) {
}
