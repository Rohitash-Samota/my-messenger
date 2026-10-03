package com.rohitsamota.my_messenger.dto;

import java.util.List;

public record MessageCursorResponseDto(
        List<MessageResponseDto> items,
        Long nextCursor,
        boolean hasMore) {
}
