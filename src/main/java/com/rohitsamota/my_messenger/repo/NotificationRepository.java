package com.rohitsamota.my_messenger.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.rohitsamota.my_messenger.entity.Notification;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    boolean existsByEventIdAndUserId(String eventId, Long userId);

    Optional<Notification> findByIdAndUserId(Long id, Long userId);

    List<Notification> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    List<Notification> findByUserIdAndIdLessThanOrderByIdDesc(
            Long userId,
            Long beforeId,
            Pageable pageable);
}
