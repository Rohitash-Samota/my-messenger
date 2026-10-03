package com.rohitsamota.my_messenger.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import com.rohitsamota.my_messenger.dto.CreateConversationRequestDto;
import com.rohitsamota.my_messenger.entity.ConversationParticipant;
import com.rohitsamota.my_messenger.entity.Conversion;
import com.rohitsamota.my_messenger.entity.User;
import com.rohitsamota.my_messenger.enums.ConversionType;
import com.rohitsamota.my_messenger.enums.Status;
import com.rohitsamota.my_messenger.event.ConversationCreatedPayload;
import com.rohitsamota.my_messenger.event.EventEnvelope;
import com.rohitsamota.my_messenger.repo.ConversationParticipantRepository;
import com.rohitsamota.my_messenger.repo.ConversionRepoI;
import com.rohitsamota.my_messenger.repo.UserInfoRepository;

@ExtendWith(MockitoExtension.class)
class ConversionServiceTests {
    @Mock
    private ConversionRepoI conversionRepository;
    @Mock
    private ConversationParticipantRepository participantRepository;
    @Mock
    private UserInfoRepository userRepository;
    @Mock
    private OutboxService outboxService;
    @Mock
    private RealtimeEventPublisher realtimeEventPublisher;

    private ConversionService conversionService;

    @BeforeEach
    void setUp() {
        conversionService = new ConversionService(
                conversionRepository,
                participantRepository,
                userRepository,
                outboxService,
                realtimeEventPublisher);
    }

    @Test
    void createsDirectConversationWithPeerIdentityAndEvent() {
        User requester = user(1L, "alice.smith@example.com");
        User recipient = user(2L, "bob.jones@example.com");
        when(userRepository.findByEmailIgnoreCase(requester.getEmail()))
                .thenReturn(Optional.of(requester));
        when(userRepository.findByEmailIgnoreCase(recipient.getEmail()))
                .thenReturn(Optional.of(recipient));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(requester));
        when(userRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(recipient));
        when(conversionRepository.findDirectBetween(eq(1L), eq(2L), any(Pageable.class)))
                .thenReturn(List.of());
        when(conversionRepository.saveAndFlush(any(Conversion.class))).thenAnswer(invocation -> {
            Conversion conversion = invocation.getArgument(0);
            conversion.setId(10L);
            return conversion;
        });
        when(participantRepository.findByConversionIdAndUserId(10L, 1L))
                .thenReturn(Optional.empty());
        when(participantRepository.findByConversionIdAndUserId(10L, 2L))
                .thenReturn(Optional.empty());

        var result = conversionService.createOrReuseDirect(
                requester.getEmail(),
                new CreateConversationRequestDto(" BOB.JONES@example.com "));

        assertTrue(result.created());
        assertEquals(10L, result.conversation().id());
        assertEquals(2L, result.conversation().peer().id());
        assertEquals("Bob Jones", result.conversation().peer().name());
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<EventEnvelope> eventCaptor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outboxService).enqueueConversationEvent(eq(10L), eventCaptor.capture());
        ConversationCreatedPayload payload = (ConversationCreatedPayload) eventCaptor.getValue().payload();
        assertEquals(List.of(1L, 2L), payload.participantUserIds());
        verify(realtimeEventPublisher).publishAfterCommit(
                eq(eventCaptor.getValue()), eq(List.of(1L, 2L)));
    }

    @Test
    void reusesExistingDirectConversationWithoutCreatingAnotherEvent() {
        User requester = user(1L, "alice@example.com");
        User recipient = user(2L, "bob@example.com");
        Conversion existing = new Conversion();
        existing.setId(10L);
        existing.setUserId(2L);
        existing.setClientId(1L);
        existing.setConversionType(ConversionType.INDIVIDUAL);
        ConversationParticipant requesterState = new ConversationParticipant(10L, 1L);
        ConversationParticipant recipientState = new ConversationParticipant(10L, 2L);

        when(userRepository.findByEmailIgnoreCase(requester.getEmail()))
                .thenReturn(Optional.of(requester));
        when(userRepository.findByEmailIgnoreCase(recipient.getEmail()))
                .thenReturn(Optional.of(recipient));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(requester));
        when(userRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(recipient));
        when(conversionRepository.findDirectBetween(eq(1L), eq(2L), any(Pageable.class)))
                .thenReturn(List.of(existing));
        when(participantRepository.findByConversionIdAndUserId(10L, 1L))
                .thenReturn(Optional.of(requesterState));
        when(participantRepository.findByConversionIdAndUserId(10L, 2L))
                .thenReturn(Optional.of(recipientState));

        var result = conversionService.createOrReuseDirect(
                requester.getEmail(),
                new CreateConversationRequestDto(recipient.getEmail()));

        assertFalse(result.created());
        assertEquals(2L, result.conversation().peer().id());
        verify(conversionRepository, never()).saveAndFlush(any());
        verify(outboxService, never()).enqueueConversationEvent(any(), any());
        verify(realtimeEventPublisher, never()).publishAfterCommit(any(), any());
    }

    private User user(Long id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setStatus(Status.ACTIVE);
        return user;
    }
}
