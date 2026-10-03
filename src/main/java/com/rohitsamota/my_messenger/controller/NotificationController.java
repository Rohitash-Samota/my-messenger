package com.rohitsamota.my_messenger.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rohitsamota.my_messenger.dto.NotificationCursorResponseDto;
import com.rohitsamota.my_messenger.dto.NotificationResponseDto;
import com.rohitsamota.my_messenger.services.NotificationService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

@RestController
@RequestMapping("/v1/api/notifications")
@Validated
public class NotificationController {
    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public NotificationCursorResponseDto list(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) @Positive Long beforeId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return notificationService.listForUser(userDetails.getUsername(), beforeId, limit);
    }

    @PatchMapping("/{notificationId}/read")
    public NotificationResponseDto markRead(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable @Positive Long notificationId) {
        return notificationService.markRead(userDetails.getUsername(), notificationId);
    }
}
