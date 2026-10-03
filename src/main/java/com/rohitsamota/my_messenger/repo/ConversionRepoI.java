package com.rohitsamota.my_messenger.repo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rohitsamota.my_messenger.entity.Conversion;

import jakarta.persistence.LockModeType;

public interface ConversionRepoI extends JpaRepository<Conversion, Long> {
	boolean existsByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    Optional<Conversion> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    @Query("""
	    select conversion from Conversion conversion
	    where conversion.userId = :userId
	      and conversion.deletedAt is null
	      and conversion.isArchive = :archived
	      and (
		    :cursorId is null
		    or (:cursorPinned = true and (
			    (conversion.isPin = true and conversion.id < :cursorId)
			    or conversion.isPin = false))
		    or (:cursorPinned = false and conversion.isPin = false and conversion.id < :cursorId)
	      )
	    order by conversion.isPin desc, conversion.id desc
	    """)
    List<Conversion> findCursorPage(
	    @Param("userId") Long userId,
	    @Param("archived") boolean archived,
	    @Param("cursorPinned") Boolean cursorPinned,
	    @Param("cursorId") Long cursorId,
	    Pageable pageable);

    @Query("""
            select conversion
            from Conversion conversion, ConversationParticipant participant
            where participant.conversionId = conversion.id
              and participant.userId = :userId
              and participant.deletedAt is null
              and participant.hiddenAt is null
              and participant.archived = :archived
              and (
                    :cursorId is null
                    or (:cursorPinned = true and (
                        (participant.pinned = true and (
                            conversion.lastActivityAt < :cursorActivityAt
                            or (conversion.lastActivityAt = :cursorActivityAt
                                and conversion.id < :cursorId)))
                        or participant.pinned = false))
                    or (:cursorPinned = false
                        and participant.pinned = false
                        and (conversion.lastActivityAt < :cursorActivityAt
                            or (conversion.lastActivityAt = :cursorActivityAt
                                and conversion.id < :cursorId)))
              )
            order by participant.pinned desc,
                     conversion.lastActivityAt desc,
                     conversion.id desc
            """)
    List<Conversion> findInboxPage(
            @Param("userId") Long userId,
            @Param("archived") boolean archived,
            @Param("cursorPinned") Boolean cursorPinned,
            @Param("cursorActivityAt") LocalDateTime cursorActivityAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select conversion from Conversion conversion where conversion.id = :id")
	Optional<Conversion> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select conversion
            from Conversion conversion
            where conversion.conversionType = com.rohitsamota.my_messenger.enums.ConversionType.INDIVIDUAL
              and conversion.deletedAt is null
              and ((conversion.userId = :firstUserId and conversion.clientId = :secondUserId)
                or (conversion.userId = :secondUserId and conversion.clientId = :firstUserId))
            order by conversion.id asc
            """)
    List<Conversion> findDirectBetween(
            @Param("firstUserId") Long firstUserId,
            @Param("secondUserId") Long secondUserId,
            Pageable pageable);

	@Query("""
			select conversion
			from Conversion conversion, ConversationParticipant participant
			where participant.conversionId = conversion.id
			  and participant.userId = :userId
			  and participant.deletedAt is null
			  and participant.hiddenAt is null
			order by conversion.lastActivityAt desc, conversion.id desc
			""")
	List<Conversion> findVisibleToUser(
			@Param("userId") Long userId,
			Pageable pageable);

	@Query("""
			select conversion
			from Conversion conversion, ConversationParticipant participant
			where participant.conversionId = conversion.id
			  and participant.userId = :userId
			  and participant.deletedAt is null
			  and participant.hiddenAt is null
			  and (conversion.lastActivityAt < :beforeActivityAt
			       or (conversion.lastActivityAt = :beforeActivityAt and conversion.id < :beforeId))
			order by conversion.lastActivityAt desc, conversion.id desc
			""")
	List<Conversion> findVisibleToUserBefore(
			@Param("userId") Long userId,
			@Param("beforeActivityAt") LocalDateTime beforeActivityAt,
			@Param("beforeId") Long beforeId,
			Pageable pageable);

	@Query("""
			select conversion
			from Conversion conversion, ConversationParticipant participant
			where conversion.id = :conversionId
			  and participant.conversionId = conversion.id
			  and participant.userId = :userId
			  and participant.deletedAt is null
			  and participant.hiddenAt is null
			""")
	Optional<Conversion> findVisibleById(
			@Param("conversionId") Long conversionId,
			@Param("userId") Long userId);
}
