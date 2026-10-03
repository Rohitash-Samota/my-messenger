package com.rohitsamota.my_messenger.dto;

import java.time.Instant;

public record SocketErrorDto(
        String code,
        String message,
        Instant occurredAt) {
}
