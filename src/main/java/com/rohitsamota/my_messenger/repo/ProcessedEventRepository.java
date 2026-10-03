package com.rohitsamota.my_messenger.repo;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rohitsamota.my_messenger.entity.ProcessedEvent;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT IGNORE INTO processed_events (consumer_name, event_id, processed_at)
            VALUES (:consumerName, :eventId, :processedAt)
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("consumerName") String consumerName,
            @Param("eventId") String eventId,
            @Param("processedAt") Instant processedAt);
}
