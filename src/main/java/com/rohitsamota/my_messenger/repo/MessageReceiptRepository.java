package com.rohitsamota.my_messenger.repo;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rohitsamota.my_messenger.entity.MessageReceipt;
import com.rohitsamota.my_messenger.enums.MessageStatus;

public interface MessageReceiptRepository extends JpaRepository<MessageReceipt, Long> {

    Optional<MessageReceipt> findByMessageIdAndUserId(Long messageId, Long userId);

    List<MessageReceipt> findByMessageIdOrderByUserIdAsc(Long messageId);

    List<MessageReceipt> findByMessageIdInOrderByMessageIdAscUserIdAsc(
            Collection<Long> messageIds);

    List<MessageReceipt> findByUserIdAndMessageIdInOrderByMessageIdAsc(
            Long userId,
            Collection<Long> messageIds);

    long countByMessageId(Long messageId);

    long countByMessageIdAndStatus(Long messageId, MessageStatus status);

    @Query("""
            select count(receipt)
            from MessageReceipt receipt, Message message
            where receipt.messageId = message.id
              and receipt.userId = :userId
              and message.conversionId = :conversionId
              and receipt.status <> com.rohitsamota.my_messenger.enums.MessageStatus.READ
            """)
    long countUnread(
            @Param("conversionId") Long conversionId,
            @Param("userId") Long userId);

    @Modifying(flushAutomatically = true)
    @Query("""
            update MessageReceipt receipt
               set receipt.status = com.rohitsamota.my_messenger.enums.MessageStatus.DELIVERED,
                   receipt.deliveredAt = coalesce(receipt.deliveredAt, :deliveredAt),
                   receipt.updatedAt = :deliveredAt,
                   receipt.version = receipt.version + 1
             where receipt.userId = :userId
               and receipt.messageId in :messageIds
               and receipt.status = com.rohitsamota.my_messenger.enums.MessageStatus.SENT
            """)
    int markDelivered(
            @Param("messageIds") Collection<Long> messageIds,
            @Param("userId") Long userId,
            @Param("deliveredAt") LocalDateTime deliveredAt);

    @Modifying(flushAutomatically = true)
    @Query("""
            update MessageReceipt receipt
               set receipt.status = com.rohitsamota.my_messenger.enums.MessageStatus.DELIVERED,
                   receipt.deliveredAt = coalesce(receipt.deliveredAt, :deliveredAt),
                   receipt.updatedAt = :deliveredAt,
                   receipt.version = receipt.version + 1
             where receipt.userId = :userId
               and receipt.status = com.rohitsamota.my_messenger.enums.MessageStatus.SENT
               and receipt.messageId in (
                    select message.id
                    from Message message
                    where message.conversionId = :conversionId
                      and message.id <= :throughMessageId
               )
            """)
    int markDeliveredThrough(
            @Param("conversionId") Long conversionId,
            @Param("userId") Long userId,
            @Param("throughMessageId") Long throughMessageId,
            @Param("deliveredAt") LocalDateTime deliveredAt);

    @Modifying(flushAutomatically = true)
    @Query("""
            update MessageReceipt receipt
               set receipt.status = com.rohitsamota.my_messenger.enums.MessageStatus.READ,
                   receipt.deliveredAt = coalesce(receipt.deliveredAt, :readAt),
                   receipt.readAt = coalesce(receipt.readAt, :readAt),
                   receipt.updatedAt = :readAt,
                   receipt.version = receipt.version + 1
             where receipt.userId = :userId
               and receipt.status <> com.rohitsamota.my_messenger.enums.MessageStatus.READ
               and receipt.messageId in (
                    select message.id
                    from Message message
                    where message.conversionId = :conversionId
                      and message.id <= :throughMessageId
               )
            """)
    int markReadThrough(
            @Param("conversionId") Long conversionId,
            @Param("userId") Long userId,
            @Param("throughMessageId") Long throughMessageId,
            @Param("readAt") LocalDateTime readAt);
}
