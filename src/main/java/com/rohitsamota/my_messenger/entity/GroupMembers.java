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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "group_members")
class GroupMembers {
    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    private Long id;

    @Column(name="user_id",nullable=false)
    private Long userId;

    @Column(name="group_id",nullable=false)
    private Long groupId;

    @Enumerated(EnumType.STRING)
    @Column(name="role",nullable=false, length=50)
    private GroupUserRole groupUserRole;

    @Column(name="deleted_at", nullable=true)
    private LocalDateTime deletedAt;

    @Column(name = "updated_at", nullable=false)
    private LocalDateTime updatedAt;

    @Column(name = "created_at", nullable=false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void setCreationTimestamps() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
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

    
}