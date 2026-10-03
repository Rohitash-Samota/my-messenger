package com.rohitsamota.my_messenger.dto;

import com.rohitsamota.my_messenger.enums.MessageType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SendMessageRequestDto(
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9._:-]+")
        String clientMessageId,
        @NotBlank @Size(max = 1000) String content,
        @NotNull MessageType messageType,
        @Positive Long parentMessageId) {
}
