package com.rohitsamota.my_messenger.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.rohitsamota.my_messenger.dto.EditMessageRequestDto;
import com.rohitsamota.my_messenger.dto.MessageCursorResponseDto;
import com.rohitsamota.my_messenger.dto.MessageResponseDto;
import com.rohitsamota.my_messenger.dto.MessageStateRequestDto;
import com.rohitsamota.my_messenger.dto.MessageStateResponseDto;
import com.rohitsamota.my_messenger.dto.SendMessageRequestDto;
import com.rohitsamota.my_messenger.services.MessageService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

@RestController
@RequestMapping("/v1/api/messages")
@Validated
public class MessageController {
    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping("/{conversionId}")
    public MessageCursorResponseDto getMessagesForConversion(
            @PathVariable @Positive Long conversionId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) @Positive Long cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return messageService.listForConversion(
                userDetails.getUsername(), conversionId, cursor, limit);
    }

    @PostMapping("/{conversionId}")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponseDto sendMessage(
            @PathVariable @Positive Long conversionId,
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody SendMessageRequestDto request) {
        return messageService.send(userDetails.getUsername(), conversionId, request);
    }

    @PatchMapping("/{conversionId}/{messageId}")
    public MessageResponseDto editMessage(
            @PathVariable @Positive Long conversionId,
            @PathVariable @Positive Long messageId,
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody EditMessageRequestDto request) {
        return messageService.edit(
                userDetails.getUsername(), conversionId, messageId, request);
    }

    @DeleteMapping("/{conversionId}/{messageId}")
    public MessageResponseDto deleteMessage(
            @PathVariable @Positive Long conversionId,
            @PathVariable @Positive Long messageId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return messageService.delete(
                userDetails.getUsername(), conversionId, messageId);
    }

    @PatchMapping("/{conversionId}/delivered")
    public MessageStateResponseDto acknowledgeDelivered(
            @PathVariable @Positive Long conversionId,
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody(required = false) MessageStateRequestDto request) {
        return messageService.acknowledgeDelivered(
                userDetails.getUsername(),
                conversionId,
                request == null ? null : request.upToMessageId());
    }

    @PatchMapping("/{conversionId}/read")
    public MessageStateResponseDto markRead(
            @PathVariable @Positive Long conversionId,
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody(required = false) MessageStateRequestDto request) {
        return messageService.markRead(
                userDetails.getUsername(),
                conversionId,
                request == null ? null : request.upToMessageId());
    }
}
