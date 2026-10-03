package com.rohitsamota.my_messenger.dto;

import jakarta.validation.constraints.NotNull;

public record ConversionFlagRequestDto(@NotNull Boolean value) {
}