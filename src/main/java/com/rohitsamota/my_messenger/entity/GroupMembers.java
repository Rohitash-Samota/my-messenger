package com.rohitsamota.my_messenger.entity;

import java.time.LocalDateTime;

import com.rohitsamota.my_messenger.enums.GroupUserRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(
        name = "group_members",
        indexes = {
                @Index(name = "idx_group_members_group_active_user", columnList = "group_id,deleted_at,user_id"),
                @Index(name = "idx_group_members_user_active_group", columnList = "user_id,deleted_at,group_id")
        })
public class GroupMembers {
    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    private Long id;

    @Column(name="user_id",nullable=false)
    private Long userId;

    @Column(name="group_id",nullable=false)
    private Long groupId;

    @Enumerated(EnumType.STRING)
    @Column(name="role",nullable=false, length=50)
    private GroupUserRole groupUserRole = GroupUserRole.MEMBER;

    @Column(name="deleted_at", nullable=true)
    private LocalDateTime deletedAt;

    @Column(name = "updated_at", nullable=false)
    private LocalDateTime updatedAt;

    @Column(name = "created_at", nullable=false, updatable = false)
    private LocalDateTime createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public GroupMembers() {
    }

    public GroupMembers(Long groupId, Long userId, GroupUserRole groupUserRole) {
        this.groupId = groupId;
        this.userId = userId;
        this.groupUserRole = groupUserRole == null ? GroupUserRole.MEMBER : groupUserRole;
    }

    @PrePersist
    void setCreationTimestamps() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (groupUserRole == null) {
            groupUserRole = GroupUserRole.MEMBER;
        }
    }

    @PreUpdate
    void setUpdateTimestamp() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getGroupId() {
        return groupId;
    }

    public void setGroupId(Long groupId) {
        this.groupId = groupId;
    }

    public GroupUserRole getGroupUserRole() {
        return groupUserRole;
    }

    public void setGroupUserRole(GroupUserRole groupUserRole) {
        this.groupUserRole = groupUserRole;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public boolean isActive() {
        return deletedAt == null;
    }

    public void leave(LocalDateTime leftAt) {
        this.deletedAt = leftAt == null ? LocalDateTime.now() : leftAt;
    }

    public void reactivate(GroupUserRole role) {
        this.deletedAt = null;
        if (role != null) {
            this.groupUserRole = role;
        }
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
