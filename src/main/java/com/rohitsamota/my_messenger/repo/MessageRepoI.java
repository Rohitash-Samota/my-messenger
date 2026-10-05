package com.rohitsamota.my_messenger.repo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rohitsamota.my_messenger.entity.Message;

import jakarta.persistence.LockModeType;

public interface MessageRepoI extends JpaRepository<Message, Long> {
    Optional<Message> findByUserIdAndClientMessageId(Long userId, String clientMessageId);

    Optional<Message> findByIdAndConversionId(Long id, Long conversionId);

    List<Message> findByConversionIdAndMediaId(Long conversionId, String mediaId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select message from Message message where message.id = :messageId and message.conversionId = :conversionId")
    Optional<Message> findForMutation(
            @Param("conversionId") Long conversionId,
            @Param("messageId") Long messageId);

    boolean existsByUserIdAndClientMessageId(Long userId, String clientMessageId);

    List<Message> findByConversionIdOrderByIdDesc(Long conversionId, Pageable pageable);

    List<Message> findByConversionIdAndIdLessThanOrderByIdDesc(
            Long conversionId,
            Long cursor,
            Pageable pageable);

    Optional<Message> findTopByConversionIdOrderByIdDesc(Long conversionId);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE messages message
               SET message.status = CASE
                       WHEN EXISTS (
                               SELECT 1 FROM message_receipts receipt
                               WHERE receipt.message_id = message.id)
                            AND NOT EXISTS (
                               SELECT 1 FROM message_receipts receipt
                               WHERE receipt.message_id = message.id
                                 AND receipt.status <> 'READ')
                           THEN 'READ'
                       WHEN EXISTS (
                               SELECT 1 FROM message_receipts receipt
                               WHERE receipt.message_id = message.id)
                            AND NOT EXISTS (
                               SELECT 1 FROM message_receipts receipt
                               WHERE receipt.message_id = message.id
                                 AND receipt.status = 'SENT')
                           THEN 'DELIVERED'
                       ELSE 'SENT'
                   END,
                   message.updated_at = :updatedAt,
                   message.version = message.version + 1
             WHERE message.conversion_id = :conversionId
               AND message.id <= :throughMessageId
               AND message.status <> CASE
                       WHEN EXISTS (
                               SELECT 1 FROM message_receipts receipt
                               WHERE receipt.message_id = message.id)
                            AND NOT EXISTS (
                               SELECT 1 FROM message_receipts receipt
                               WHERE receipt.message_id = message.id
                                 AND receipt.status <> 'READ')
                           THEN 'READ'
                       WHEN EXISTS (
                               SELECT 1 FROM message_receipts receipt
                               WHERE receipt.message_id = message.id)
                            AND NOT EXISTS (
                               SELECT 1 FROM message_receipts receipt
                               WHERE receipt.message_id = message.id
                                 AND receipt.status = 'SENT')
                           THEN 'DELIVERED'
                       ELSE 'SENT'
                   END
            """, nativeQuery = true)
    int recomputeAggregateStatusesThrough(
            @Param("conversionId") Long conversionId,
            @Param("throughMessageId") Long throughMessageId,
            @Param("updatedAt") LocalDateTime updatedAt);
}
