package com.rohitsamota.my_messenger.services;

import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.rohitsamota.my_messenger.dto.UserSummaryDto;
import com.rohitsamota.my_messenger.enums.Status;
import com.rohitsamota.my_messenger.repo.UserInfoRepository;

@Service
public class UserDirectoryService {
    private final UserInfoRepository userRepository;

    public UserDirectoryService(UserInfoRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<UserSummaryDto> search(String currentUserEmail, String query, int limit) {
        Long currentUserId = userRepository.findByEmailIgnoreCase(currentUserEmail)
                .map(user -> user.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "User not found"));
        String normalizedQuery = query == null
                ? ""
                : query.strip().toLowerCase(Locale.ROOT);
        return userRepository.searchDirectory(
                        currentUserId,
                        Status.ACTIVE,
                        normalizedQuery,
                        PageRequest.of(0, limit))
                .stream()
                .map(UserSummaryDto::from)
                .toList();
    }
}
