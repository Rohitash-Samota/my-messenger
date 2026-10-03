package com.rohitsamota.my_messenger.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.rohitsamota.my_messenger.dto.CallSignalRequestDto;
import com.rohitsamota.my_messenger.entity.Conversion;
import com.rohitsamota.my_messenger.entity.User;
import com.rohitsamota.my_messenger.enums.CallMediaType;
import com.rohitsamota.my_messenger.enums.CallSignalType;
import com.rohitsamota.my_messenger.enums.ConversionType;
import com.rohitsamota.my_messenger.enums.Status;
import com.rohitsamota.my_messenger.repo.ConversationParticipantRepository;
import com.rohitsamota.my_messenger.repo.ConversionRepoI;
import com.rohitsamota.my_messenger.repo.UserInfoRepository;

@ExtendWith(MockitoExtension.class)
class CallSignalingServiceTests {
    @Mock
    private UserInfoRepository userRepository;
    @Mock
    private ConversionRepoI conversionRepository;
    @Mock
    private ConversationParticipantRepository participantRepository;

    private CallSignalingService service;
    private User caller;
    private User recipient;
    private Conversion conversation;

    @BeforeEach
    void setUp() {
        service = new CallSignalingService(
                userRepository,
                conversionRepository,
                participantRepository);
        caller = user(1L, "caller@example.com");
        recipient = user(2L, "recipient@example.com");
        conversation = new Conversion();
        conversation.setId(10L);
        conversation.setUserId(1L);
        conversation.setClientId(2L);
        conversation.setConversionType(ConversionType.INDIVIDUAL);
    }

    @Test
    void bindsFirstAcceptingRecipientSessionAndRoutesOfferOnlyToIt() {
        UUID callId = UUID.randomUUID();
        stubInviteLookups();

        var invite = service.authorizeAndRoute(
                caller.getEmail(),
                "caller-session",
                request(callId, CallSignalType.INVITE, CallMediaType.VIDEO, null));
        assertEquals(recipient.getEmail(), invite.recipientEmail());
        assertNull(invite.recipientSessionId());

        when(userRepository.findByEmailIgnoreCase(recipient.getEmail()))
                .thenReturn(Optional.of(recipient));
        var accept = service.authorizeAndRoute(
                recipient.getEmail(),
                "recipient-session",
                request(callId, CallSignalType.ACCEPT, CallMediaType.VIDEO, null));
        assertEquals("caller-session", accept.recipientSessionId());

        when(userRepository.findByEmailIgnoreCase(caller.getEmail()))
                .thenReturn(Optional.of(caller));
        var offer = service.authorizeAndRoute(
                caller.getEmail(),
                "caller-session",
                request(callId, CallSignalType.OFFER, CallMediaType.VIDEO, "v=0\r\n"));
        assertEquals(recipient.getEmail(), offer.recipientEmail());
        assertEquals("recipient-session", offer.recipientSessionId());
        assertEquals("v=0\r\n", offer.signal().sdp());
    }

    @Test
    void rejectsAcceptFromSecondDeviceAfterCallWasBound() {
        UUID callId = UUID.randomUUID();
        stubInviteLookups();
        service.authorizeAndRoute(
                caller.getEmail(),
                "caller-session",
                request(callId, CallSignalType.INVITE, CallMediaType.AUDIO, null));
        when(userRepository.findByEmailIgnoreCase(recipient.getEmail()))
                .thenReturn(Optional.of(recipient));
        service.authorizeAndRoute(
                recipient.getEmail(),
                "recipient-session-1",
                request(callId, CallSignalType.ACCEPT, CallMediaType.AUDIO, null));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.authorizeAndRoute(
                        recipient.getEmail(),
                        "recipient-session-2",
                        request(callId, CallSignalType.ACCEPT, CallMediaType.AUDIO, null)));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
    }

    @Test
    void rejectsSdpOnLifecycleOnlySignal() {
        UUID callId = UUID.randomUUID();

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.authorizeAndRoute(
                        caller.getEmail(),
                        "caller-session",
                        request(callId, CallSignalType.INVITE, CallMediaType.AUDIO, "unexpected")));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    private void stubInviteLookups() {
        when(userRepository.findByEmailIgnoreCase(caller.getEmail()))
                .thenReturn(Optional.of(caller));
        when(conversionRepository.findVisibleById(10L, caller.getId()))
                .thenReturn(Optional.of(conversation));
        when(participantRepository.findActiveUserIdsByConversionId(10L))
                .thenReturn(List.of(caller.getId(), recipient.getId()));
        when(userRepository.findById(recipient.getId())).thenReturn(Optional.of(recipient));
    }

    private CallSignalRequestDto request(
            UUID callId,
            CallSignalType type,
            CallMediaType mediaType,
            String sdp) {
        return new CallSignalRequestDto(callId, 10L, type, mediaType, sdp, null);
    }

    private User user(Long id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setStatus(Status.ACTIVE);
        return user;
    }
}
