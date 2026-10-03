package com.rohitsamota.my_messenger.dto;

import jakarta.validation.constraints.Positive;

public record MessageStateRequestDto(@Positive Long upToMessageId) {
}
