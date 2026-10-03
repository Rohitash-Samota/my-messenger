package com.rohitsamota.my_messenger.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rohitsamota.my_messenger.dto.ConversionCursorResponseDto;
import com.rohitsamota.my_messenger.dto.ConversionFlagRequestDto;
import com.rohitsamota.my_messenger.dto.ConversionResponseDto;
import com.rohitsamota.my_messenger.services.ConversionService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

@RestController
@RequestMapping("/v1/api/conversions")
@Validated
public class ConversionController {
    private final ConversionService conversionService;

    public ConversionController(ConversionService conversionService) {
        this.conversionService = conversionService;
    }

    @GetMapping
    public ConversionCursorResponseDto getConversions(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return conversionService.listForUser(userDetails.getUsername(), cursor, archived, limit);
    }

    @PatchMapping("/{conversionId}/pin")
    public ConversionResponseDto setPinned(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable @Positive Long conversionId,
            @Valid @RequestBody ConversionFlagRequestDto request) {
        return conversionService.setPinned(userDetails.getUsername(), conversionId, request.value());
    }

    @PatchMapping("/{conversionId}/archive")
    public ConversionResponseDto setArchived(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable @Positive Long conversionId,
            @Valid @RequestBody ConversionFlagRequestDto request) {
        return conversionService.setArchived(userDetails.getUsername(), conversionId, request.value());
    }

}