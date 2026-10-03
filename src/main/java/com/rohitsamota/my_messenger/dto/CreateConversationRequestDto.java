package com.rohitsamota.my_messenger.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateConversationRequestDto(
        @NotBlank @Email @Size(max = 50) String recipientEmail) {
}
