package com.rohitsamota.my_messenger.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rohitsamota.my_messenger.entity.GroupMembers;

public interface GroupMemberRepository extends JpaRepository<GroupMembers, Long> {

    Optional<GroupMembers> findByGroupIdAndUserIdAndDeletedAtIsNull(Long groupId, Long userId);

    boolean existsByGroupIdAndUserIdAndDeletedAtIsNull(Long groupId, Long userId);

    List<GroupMembers> findByGroupIdAndDeletedAtIsNullOrderByIdAsc(Long groupId);

    long countByGroupIdAndDeletedAtIsNull(Long groupId);

    @Query("""
            select distinct member.userId
            from GroupMembers member
            where member.groupId = :groupId
              and member.deletedAt is null
            order by member.userId
            """)
    List<Long> findActiveUserIdsByGroupId(@Param("groupId") Long groupId);
}
