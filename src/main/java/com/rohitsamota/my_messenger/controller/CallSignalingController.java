package com.rohitsamota.my_messenger.controller;

import java.security.Principal;
import java.time.Instant;

import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ResponseStatusException;

import com.rohitsamota.my_messenger.dto.CallSignalRequestDto;
import com.rohitsamota.my_messenger.dto.SocketErrorDto;
import com.rohitsamota.my_messenger.services.CallSignalingService;

import jakarta.validation.Valid;

@Controller
@Validated
public class CallSignalingController {
    private final CallSignalingService signalingService;
    private final SimpMessagingTemplate messagingTemplate;
    private final SimpUserRegistry userRegistry;

    public CallSignalingController(
            CallSignalingService signalingService,
            SimpMessagingTemplate messagingTemplate,
            SimpUserRegistry userRegistry) {
        this.signalingService = signalingService;
        this.messagingTemplate = messagingTemplate;
        this.userRegistry = userRegistry;
    }

    @MessageMapping("/calls.signal")
    public void signal(
            Principal principal,
            @Header("simpSessionId") String sessionId,
            @Valid @Payload CallSignalRequestDto request) {
        if (principal == null) {
            throw new IllegalStateException("Authenticated WebSocket session required");
        }
        var outbound = signalingService.authorizeAndRoute(
                principal.getName(),
                sessionId,
                request);
        if (request.type() == com.rohitsamota.my_messenger.enums.CallSignalType.INVITE
                && userRegistry.getUser(outbound.recipientEmail()) == null) {
            signalingService.cancel(request.callId());
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Call recipient is offline");
        }
        sendToSession(outbound);
    }

    private void sendToSession(CallSignalingService.RoutedSignal outbound) {
        if (outbound.recipientSessionId() == null) {
            messagingTemplate.convertAndSendToUser(
                    outbound.recipientEmail(),
                    "/queue/calls",
                    outbound.signal());
            return;
        }
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(outbound.recipientSessionId());
        headers.setLeaveMutable(true);
        messagingTemplate.convertAndSendToUser(
                outbound.recipientEmail(),
                "/queue/calls",
                outbound.signal(),
                headers.getMessageHeaders());
    }

    @MessageExceptionHandler
    @SendToUser(value = "/queue/errors", broadcast = false)
    public SocketErrorDto handleException(Exception exception) {
        String message = "Call signaling request was rejected";
        String code = "call_signal_rejected";
        if (exception instanceof ResponseStatusException statusException) {
            message = statusException.getReason() == null ? message : statusException.getReason();
            code = "call_signal_" + statusException.getStatusCode().value();
        }
        return new SocketErrorDto(code, message, Instant.now());
    }
}
