package com.rohitsamota.my_messenger.dto;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import com.rohitsamota.my_messenger.entity.User;

public record UserSummaryDto(
        Long id,
        String email,
        String name,
        String profilePhoto) {

    public static UserSummaryDto from(User user) {
        return new UserSummaryDto(
                user.getId(),
                user.getEmail(),
                displayName(user.getEmail()),
                user.getProfilePhoto());
    }

    private static String displayName(String email) {
        String localPart = email == null ? "" : email.split("@", 2)[0];
        String normalized = localPart.replaceAll("[._-]+", " ").strip();
        if (normalized.isBlank()) {
            return email;
        }
        return Arrays.stream(normalized.split("\\s+"))
                .map(part -> part.substring(0, 1).toUpperCase(Locale.ROOT)
                        + part.substring(1))
                .collect(Collectors.joining(" "));
    }
}
