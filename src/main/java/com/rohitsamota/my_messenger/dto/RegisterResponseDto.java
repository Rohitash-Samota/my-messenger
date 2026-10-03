package com.rohitsamota.my_messenger.dto;

public record RegisterResponseDto(
	String accessToken,
	String tokenType,
	long expiresInSeconds,
	String refreshToken,
	long refreshTokenExpiresInSeconds) {
}