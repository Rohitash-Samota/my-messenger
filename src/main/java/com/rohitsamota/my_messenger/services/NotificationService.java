package com.rohitsamota.my_messenger.services;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.rohitsamota.my_messenger.dto.NotificationCursorResponseDto;
import com.rohitsamota.my_messenger.dto.NotificationResponseDto;
import com.rohitsamota.my_messenger.entity.Notification;
import com.rohitsamota.my_messenger.event.NotificationPayload;
import com.rohitsamota.my_messenger.repo.NotificationRepository;
import com.rohitsamota.my_messenger.repo.UserInfoRepository;

@Service
public class NotificationService {
    private final NotificationRepository notificationRepository;
    private final UserInfoRepository userRepository;

    public NotificationService(
            NotificationRepository notificationRepository,
            UserInfoRepository userRepository) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public void store(UUID eventId, NotificationPayload payload) {
        if (notificationRepository.existsByEventIdAndUserId(
                eventId.toString(), payload.recipientUserId())) {
            return;
        }
        notificationRepository.save(new Notification(
                eventId,
                payload.recipientUserId(),
                payload.conversionId(),
                payload.messageId(),
                payload.title(),
                payload.body()));
    }

    @Transactional(readOnly = true)
    public NotificationCursorResponseDto listForUser(
            String email,
            Long beforeId,
            int limit) {
        Long userId = currentUserId(email);
        var pageRequest = PageRequest.of(0, limit + 1);
        List<Notification> fetched = beforeId == null
                ? notificationRepository.findByUserIdOrderByIdDesc(userId, pageRequest)
                : notificationRepository.findByUserIdAndIdLessThanOrderByIdDesc(
                        userId, beforeId, pageRequest);

        boolean hasMore = fetched.size() > limit;
        List<Notification> page = hasMore ? fetched.subList(0, limit) : fetched;
        Long nextCursor = hasMore ? page.get(page.size() - 1).getId() : null;
        return new NotificationCursorResponseDto(
                page.stream().map(this::toResponse).toList(),
                nextCursor,
                hasMore);
    }

    @Transactional
    public NotificationResponseDto markRead(String email, Long notificationId) {
        Long userId = currentUserId(email);
        Notification notification = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Notification not found"));
        notification.markRead();
        return toResponse(notification);
    }

    private Long currentUserId(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .map(user -> user.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "User not found"));
    }

    private NotificationResponseDto toResponse(Notification notification) {
        return new NotificationResponseDto(
                notification.getId(),
                notification.getConversionId(),
                notification.getMessageId(),
                notification.getTitle(),
                notification.getBody(),
                notification.getReadAt(),
                notification.getCreatedAt());
    }
}
