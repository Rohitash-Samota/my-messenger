package com.rohitsamota.my_messenger.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import com.rohitsamota.my_messenger.services.JwtService;

class JwtStompChannelInterceptorTests {
    private JwtService jwtService;
    private UserDetailsService userDetailsService;
    private JwtStompChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        userDetailsService = mock(UserDetailsService.class);
        interceptor = new JwtStompChannelInterceptor(jwtService, userDetailsService);
    }

    @Test
    void connectAuthenticatesTheOriginalAccessorAndRemovesTheTokenHeader() {
        var user = User.withUsername("caller@example.com")
                .password("unused")
                .roles("USER")
                .build();
        when(jwtService.extractUsername("valid-token")).thenReturn(user.getUsername());
        when(userDetailsService.loadUserByUsername(user.getUsername())).thenReturn(user);
        when(jwtService.isTokenValid("valid-token", user)).thenReturn(true);
        when(jwtService.extractExpirationEpochMillis("valid-token"))
                .thenReturn(System.currentTimeMillis() + 60_000L);

        StompHeaderAccessor original = StompHeaderAccessor.create(StompCommand.CONNECT);
        original.setSessionId("session-1");
        original.setSessionAttributes(new HashMap<>());
        original.setNativeHeader("Authorization", "Bearer valid-token");
        original.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(
                new byte[0],
                original.getMessageHeaders());

        Message<?> returned = interceptor.preSend(message, mock(MessageChannel.class));
        StompHeaderAccessor returnedAccessor = MessageHeaderAccessor.getAccessor(
                returned,
                StompHeaderAccessor.class);

        assertSame(message, returned);
        assertNotNull(returnedAccessor);
        assertEquals(user.getUsername(), returnedAccessor.getUser().getName());
        assertNull(returnedAccessor.getFirstNativeHeader("Authorization"));
    }

    @Test
    void connectRejectsMissingBearerToken() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionAttributes(new HashMap<>());
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(
                new byte[0],
                accessor.getMessageHeaders());

        assertThrows(
                MessageDeliveryException.class,
                () -> interceptor.preSend(message, mock(MessageChannel.class)));
    }

    @Test
    void authenticatedSessionMaySubscribeToRealtimeEvents() {
        var user = User.withUsername("caller@example.com")
                .password("unused")
                .roles("USER")
                .build();
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId("session-1");
        var sessionAttributes = new HashMap<String, Object>();
        sessionAttributes.put("call.jwtExpiresAt", System.currentTimeMillis() + 60_000L);
        accessor.setSessionAttributes(sessionAttributes);
        accessor.setUser(new UsernamePasswordAuthenticationToken(
                user, null, user.getAuthorities()));
        accessor.setDestination("/user/queue/events");
        accessor.setSubscriptionId("events");
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(
                new byte[0],
                accessor.getMessageHeaders());

        assertSame(message, interceptor.preSend(message, mock(MessageChannel.class)));
    }
}
