package com.rohitsamota.my_messenger.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Table(
        name = "conversation_participants",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_conversation_participants_conversion_user",
                columnNames = {"conversion_id", "user_id"}),
        indexes = {
                @Index(
                        name = "idx_conversation_participants_user_active",
                        columnList = "user_id,deleted_at,hidden_at,conversion_id"),
                @Index(
                        name = "idx_conversation_participants_conversion_active",
                        columnList = "conversion_id,deleted_at,user_id")
        })
public class ConversationParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversion_id", nullable = false)
    private Long conversionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "is_pin", nullable = false)
    private boolean pinned;

    @Column(name = "is_archive", nullable = false)
    private boolean archived;

    @Column(name = "unread_count", nullable = false)
    private long unreadCount;

    @Column(name = "last_delivered_message_id")
    private Long lastDeliveredMessageId;

    @Column(name = "last_read_message_id")
    private Long lastReadMessageId;

    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    @Column(name = "hidden_at")
    private LocalDateTime hiddenAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public ConversationParticipant() {
    }

    public ConversationParticipant(Long conversionId, Long userId) {
        this.conversionId = conversionId;
        this.userId = userId;
    }

    @PrePersist
    void setCreationTimestamps() {
        LocalDateTime now = LocalDateTime.now();
        if (joinedAt == null) {
            joinedAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (unreadCount < 0) {
            throw new IllegalStateException("Unread count cannot be negative");
        }
    }

    @PreUpdate
    void setUpdateTimestamp() {
        updatedAt = LocalDateTime.now();
        if (unreadCount < 0) {
            throw new IllegalStateException("Unread count cannot be negative");
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getConversionId() {
        return conversionId;
    }

    public void setConversionId(Long conversionId) {
        this.conversionId = conversionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public long getUnreadCount() {
        return unreadCount;
    }

    public void setUnreadCount(long unreadCount) {
        if (unreadCount < 0) {
            throw new IllegalArgumentException("Unread count cannot be negative");
        }
        this.unreadCount = unreadCount;
    }

    public void incrementUnread() {
        unreadCount = Math.addExact(unreadCount, 1L);
    }

    public Long getLastDeliveredMessageId() {
        return lastDeliveredMessageId;
    }

    public void setLastDeliveredMessageId(Long lastDeliveredMessageId) {
        this.lastDeliveredMessageId = lastDeliveredMessageId;
    }

    public void recordDeliveredThrough(Long messageId) {
        if (messageId != null
                && (lastDeliveredMessageId == null || messageId > lastDeliveredMessageId)) {
            lastDeliveredMessageId = messageId;
        }
    }

    public Long getLastReadMessageId() {
        return lastReadMessageId;
    }

    public void setLastReadMessageId(Long lastReadMessageId) {
        this.lastReadMessageId = lastReadMessageId;
    }

    public void recordReadThrough(Long messageId, long remainingUnread) {
        if (remainingUnread < 0) {
            throw new IllegalArgumentException("Unread count cannot be negative");
        }
        if (messageId != null && (lastReadMessageId == null || messageId > lastReadMessageId)) {
            lastReadMessageId = messageId;
            recordDeliveredThrough(messageId);
        }
        unreadCount = remainingUnread;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(LocalDateTime joinedAt) {
        this.joinedAt = joinedAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public LocalDateTime getHiddenAt() {
        return hiddenAt;
    }

    public void setHiddenAt(LocalDateTime hiddenAt) {
        this.hiddenAt = hiddenAt;
    }

    public boolean isHidden() {
        return hiddenAt != null;
    }

    public void hide(LocalDateTime hiddenAt) {
        this.hiddenAt = hiddenAt == null ? LocalDateTime.now() : hiddenAt;
    }

    public void show() {
        this.hiddenAt = null;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public boolean isActive() {
        return deletedAt == null;
    }

    public void leave(LocalDateTime leftAt) {
        deletedAt = leftAt == null ? LocalDateTime.now() : leftAt;
    }

    public void reactivate(LocalDateTime rejoinedAt) {
        deletedAt = null;
        hiddenAt = null;
        joinedAt = rejoinedAt == null ? LocalDateTime.now() : rejoinedAt;
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
