package com.rohitsamota.my_messenger.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.rohitsamota.my_messenger.services.AuthService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request.email(), request.password(), request.mobileNumber());
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }

    @GetMapping("/me")
    public CurrentUserResponse currentUser(@AuthenticationPrincipal UserDetails userDetails) {
        return new CurrentUserResponse(userDetails.getUsername());
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 50) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @Positive Long mobileNumber) {
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 50) String email,
            @NotBlank String password) {
    }

        public record RefreshRequest(@NotBlank @Size(max = 128) String refreshToken) {
        }

        public record AuthResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            String refreshToken,
            long refreshTokenExpiresInSeconds) {
    }

    public record CurrentUserResponse(String email) {
    }
}