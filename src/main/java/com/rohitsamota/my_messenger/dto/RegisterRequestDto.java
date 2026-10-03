package com.rohitsamota.my_messenger.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record RegisterRequestDto(
	@NotBlank 
    @Email 
    @Size(max = 50) 
    String email,

	@NotBlank 
    @Size(min = 8, max = 72) 
    String password,
    
	@Positive
    @Digits(integer = 10, fraction = 0) 
    Long mobileNumber) {
}