package com.rohitsamota.my_messenger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EditMessageRequestDto(
        @NotBlank @Size(max = 1000) String content) {
}
