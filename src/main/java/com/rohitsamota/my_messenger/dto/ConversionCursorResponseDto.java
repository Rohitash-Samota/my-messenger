package com.rohitsamota.my_messenger.dto;

import java.util.List;

public record ConversionCursorResponseDto(
        List<ConversionResponseDto> items,
        String nextCursor,
        boolean hasMore) {
}