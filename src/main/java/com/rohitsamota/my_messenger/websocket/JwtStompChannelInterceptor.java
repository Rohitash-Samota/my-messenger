package com.rohitsamota.my_messenger.websocket;

import java.util.Set;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;

import com.rohitsamota.my_messenger.services.JwtService;

import io.jsonwebtoken.JwtException;

@Component
public class JwtStompChannelInterceptor implements ChannelInterceptor {
    private static final String JWT_EXPIRES_AT = "call.jwtExpiresAt";
    private static final Set<String> ALLOWED_SUBSCRIPTIONS = Set.of(
            "/user/queue/calls",
            "/user/queue/events",
            "/user/queue/errors");

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    public JwtStompChannelInterceptor(
            JwtService jwtService,
            UserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                message,
                StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(command) || StompCommand.STOMP.equals(command)) {
            authenticate(accessor);
        } else if (StompCommand.SEND.equals(command) || StompCommand.SUBSCRIBE.equals(command)) {
            if (!(accessor.getUser() instanceof Authentication authentication)
                    || !authentication.isAuthenticated()) {
                throw new MessageDeliveryException("Authenticated WebSocket session required");
            }
            ensureTokenIsCurrent(accessor);
            authorizeDestination(accessor, command);
        }

        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String authorization = accessor.getFirstNativeHeader("Authorization");
        if (authorization == null) {
            authorization = accessor.getFirstNativeHeader("authorization");
        }
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new MessageDeliveryException("Bearer token is required");
        }

        try {
            String token = authorization.substring(7);
            String username = jwtService.extractUsername(token);
            var userDetails = userDetailsService.loadUserByUsername(username);
            if (!jwtService.isTokenValid(token, userDetails)) {
                throw new MessageDeliveryException("Bearer token is invalid or expired");
            }
            accessor.setUser(new UsernamePasswordAuthenticationToken(
                    userDetails,
                    null,
                    userDetails.getAuthorities()));
            var sessionAttributes = accessor.getSessionAttributes();
            if (sessionAttributes == null) {
                throw new MessageDeliveryException("WebSocket session attributes are unavailable");
            }
            sessionAttributes.put(JWT_EXPIRES_AT, jwtService.extractExpirationEpochMillis(token));
            accessor.removeNativeHeader("Authorization");
            accessor.removeNativeHeader("authorization");
        } catch (JwtException | IllegalArgumentException | AuthenticationException exception) {
            throw new MessageDeliveryException("Bearer token is invalid or expired");
        }
    }

    private void ensureTokenIsCurrent(StompHeaderAccessor accessor) {
        Object expiresAt = accessor.getSessionAttributes() == null
                ? null
                : accessor.getSessionAttributes().get(JWT_EXPIRES_AT);
        if (!(expiresAt instanceof Long expiration) || expiration <= System.currentTimeMillis()) {
            throw new MessageDeliveryException("Bearer token is invalid or expired");
        }
    }

    private void authorizeDestination(StompHeaderAccessor accessor, StompCommand command) {
        String destination = accessor.getDestination();
        if (StompCommand.SEND.equals(command) && !"/app/calls.signal".equals(destination)) {
            throw new MessageDeliveryException("Sending to this destination is not allowed");
        }
        if (StompCommand.SUBSCRIBE.equals(command) && !ALLOWED_SUBSCRIPTIONS.contains(destination)) {
            throw new MessageDeliveryException("Subscribing to this destination is not allowed");
        }
    }
}
