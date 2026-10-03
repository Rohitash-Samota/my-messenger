package com.rohitsamota.my_messenger.dto;

public record LoginResponseDto(
	String accessToken,
	String tokenType,
	long expiresInSeconds,
	String refreshToken,
	Long refreshTokenExpiresInSeconds) {
}