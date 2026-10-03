package com.rohitsamota.my_messenger.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rohitsamota.my_messenger.dto.UserSummaryDto;
import com.rohitsamota.my_messenger.services.UserDirectoryService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/v1/api/users")
@Validated
public class UserController {
    private final UserDirectoryService userDirectoryService;

    public UserController(UserDirectoryService userDirectoryService) {
        this.userDirectoryService = userDirectoryService;
    }

    @GetMapping
    public List<UserSummaryDto> search(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return userDirectoryService.search(userDetails.getUsername(), q, limit);
    }
}
