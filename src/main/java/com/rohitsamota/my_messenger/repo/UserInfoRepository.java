package com.rohitsamota.my_messenger.repo;

import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.rohitsamota.my_messenger.entity.User;
import com.rohitsamota.my_messenger.enums.Status;

import jakarta.persistence.LockModeType;

@Repository
public interface UserInfoRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from User user where user.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select user
            from User user
            where user.id <> :currentUserId
              and user.status = :status
              and (:query = '' or lower(user.email) like concat('%', :query, '%'))
            order by user.email asc, user.id asc
            """)
    java.util.List<User> searchDirectory(
            @Param("currentUserId") Long currentUserId,
            @Param("status") Status status,
            @Param("query") String query,
            Pageable pageable);
}
