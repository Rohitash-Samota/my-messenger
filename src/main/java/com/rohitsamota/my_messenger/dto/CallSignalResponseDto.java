package com.rohitsamota.my_messenger.dto;

import java.time.Instant;
import java.util.UUID;

import com.rohitsamota.my_messenger.enums.CallMediaType;
import com.rohitsamota.my_messenger.enums.CallSignalType;

public record CallSignalResponseDto(
        UUID callId,
        Long conversationId,
        Long senderUserId,
        String sender,
        CallSignalType type,
        CallMediaType mediaType,
        String sdp,
        IceCandidateDto candidate,
        Instant sentAt) {
}
