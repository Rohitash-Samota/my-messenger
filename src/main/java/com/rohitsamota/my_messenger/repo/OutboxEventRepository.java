package com.rohitsamota.my_messenger.repo;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rohitsamota.my_messenger.entity.OutboxEvent;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE outbox_events
               SET lock_owner = :lockOwner,
                   locked_until = :lockedUntil,
                   updated_at = :now
             WHERE status = 'PENDING'
               AND available_at <= :now
               AND (locked_until IS NULL OR locked_until <= :now)
               AND attempt_count < :maxAttempts
             ORDER BY id
             LIMIT :batchSize
            """, nativeQuery = true)
    int claimBatch(
            @Param("lockOwner") String lockOwner,
            @Param("now") Instant now,
            @Param("lockedUntil") Instant lockedUntil,
            @Param("maxAttempts") int maxAttempts,
            @Param("batchSize") int batchSize);

    List<OutboxEvent> findByLockOwnerOrderByIdAsc(String lockOwner);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE outbox_events
               SET status = 'PUBLISHED',
                   published_at = :publishedAt,
                   updated_at = :publishedAt,
                   lock_owner = NULL,
                   locked_until = NULL,
                   last_error = NULL
             WHERE event_id = :eventId
               AND lock_owner = :lockOwner
               AND status = 'PENDING'
            """, nativeQuery = true)
    int markPublished(
            @Param("eventId") String eventId,
            @Param("lockOwner") String lockOwner,
            @Param("publishedAt") Instant publishedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE outbox_events
               SET status = CASE
                       WHEN attempt_count + 1 >= :maxAttempts THEN 'FAILED'
                       ELSE 'PENDING'
                   END,
                   attempt_count = attempt_count + 1,
                   available_at = :availableAt,
                   last_error = :lastError,
                   updated_at = :failedAt,
                   lock_owner = NULL,
                   locked_until = NULL
             WHERE event_id = :eventId
               AND lock_owner = :lockOwner
               AND status = 'PENDING'
            """, nativeQuery = true)
    int markFailed(
            @Param("eventId") String eventId,
            @Param("lockOwner") String lockOwner,
            @Param("failedAt") Instant failedAt,
            @Param("availableAt") Instant availableAt,
            @Param("lastError") String lastError,
            @Param("maxAttempts") int maxAttempts);
}
