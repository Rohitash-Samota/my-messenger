package com.rohitsamota.my_messenger.dto;

import com.rohitsamota.my_messenger.entity.User;

public record CurrentUserResponseDto(
        Long id,
        String email,
        String name,
        Long mobileNumber,
        String profilePhoto) {

    public static CurrentUserResponseDto from(User user) {
        UserSummaryDto summary = UserSummaryDto.from(user);
        return new CurrentUserResponseDto(
                user.getId(),
                user.getEmail(),
                summary.name(),
                user.getMobileNumber(),
                user.getProfilePhoto());
    }
}
