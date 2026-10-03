package com.rohitsamota.my_messenger.repo;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rohitsamota.my_messenger.entity.ConversationParticipant;

import jakarta.persistence.LockModeType;

public interface ConversationParticipantRepository
        extends JpaRepository<ConversationParticipant, Long> {

    Optional<ConversationParticipant> findByConversionIdAndUserId(
            Long conversionId,
            Long userId);

    Optional<ConversationParticipant> findByConversionIdAndUserIdAndDeletedAtIsNull(
            Long conversionId,
            Long userId);

    boolean existsByConversionIdAndUserIdAndDeletedAtIsNull(Long conversionId, Long userId);

    List<ConversationParticipant> findByConversionIdAndDeletedAtIsNullOrderByIdAsc(
            Long conversionId);

    List<ConversationParticipant> findByUserIdAndConversionIdInAndDeletedAtIsNull(
            Long userId,
            Collection<Long> conversionIds);

    long countByConversionIdAndDeletedAtIsNull(Long conversionId);

    @Query("""
            select participant.userId
            from ConversationParticipant participant
            where participant.conversionId = :conversionId
              and participant.deletedAt is null
            order by participant.userId
            """)
    List<Long> findActiveUserIdsByConversionId(@Param("conversionId") Long conversionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select participant
            from ConversationParticipant participant
            where participant.conversionId = :conversionId
              and participant.userId = :userId
              and participant.deletedAt is null
            """)
    Optional<ConversationParticipant> findActiveForUpdate(
            @Param("conversionId") Long conversionId,
            @Param("userId") Long userId);

    @Modifying(flushAutomatically = true)
    @Query("""
            update ConversationParticipant participant
               set participant.unreadCount = participant.unreadCount + 1,
                   participant.updatedAt = CURRENT_TIMESTAMP,
                   participant.version = participant.version + 1
             where participant.conversionId = :conversionId
               and participant.userId in :recipientIds
               and participant.deletedAt is null
            """)
    int incrementUnreadForRecipients(
            @Param("conversionId") Long conversionId,
            @Param("recipientIds") Collection<Long> recipientIds);
}
