package com.rohitsamota.my_messenger.dto;

import java.util.UUID;

import com.rohitsamota.my_messenger.enums.CallMediaType;
import com.rohitsamota.my_messenger.enums.CallSignalType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CallSignalRequestDto(
        @NotNull UUID callId,
        @NotNull @Positive Long conversationId,
        @NotNull CallSignalType type,
        CallMediaType mediaType,
        @Size(max = 49152) String sdp,
        @Valid IceCandidateDto candidate) {
}
