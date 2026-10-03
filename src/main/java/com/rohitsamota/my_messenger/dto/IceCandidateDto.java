package com.rohitsamota.my_messenger.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record IceCandidateDto(
        @NotBlank @Size(max = 4096) String candidate,
        @Size(max = 256) String sdpMid,
        @PositiveOrZero @Max(64) Integer sdpMLineIndex,
        @Size(max = 256) String usernameFragment) {
}
