package com.rohitsamota.my_messenger.entity;

import java.time.LocalDateTime;

import com.rohitsamota.my_messenger.enums.MessageStatus;

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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Table(
        name = "message_receipts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_message_receipts_message_user",
                columnNames = {"message_id", "user_id"}),
        indexes = {
                @Index(
                        name = "idx_message_receipts_user_status_message",
                        columnList = "user_id,status,message_id"),
                @Index(
                        name = "idx_message_receipts_message_status",
                        columnList = "message_id,status")
        })
public class MessageReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MessageStatus status = MessageStatus.SENT;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "legacy_backfilled", nullable = false)
    private boolean legacyBackfilled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public MessageReceipt() {
    }

    public MessageReceipt(Long messageId, Long userId) {
        this.messageId = messageId;
        this.userId = userId;
    }

    @PrePersist
    void setCreationTimestamps() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null) {
            status = MessageStatus.SENT;
        }
        rejectUnsupportedStatus(status);
        normalizeTimestamps(now);
    }

    @PreUpdate
    void setUpdateTimestamp() {
        LocalDateTime now = LocalDateTime.now();
        updatedAt = now;
        rejectUnsupportedStatus(status);
        normalizeTimestamps(now);
    }

    private void rejectUnsupportedStatus(MessageStatus candidate) {
        if (candidate == MessageStatus.FAILED) {
            throw new IllegalStateException("FAILED is not a recipient receipt state");
        }
    }

    private void normalizeTimestamps(LocalDateTime fallback) {
        if ((status == MessageStatus.DELIVERED || status == MessageStatus.READ)
                && deliveredAt == null) {
            deliveredAt = fallback;
        }
        if (status == MessageStatus.READ && readAt == null) {
            readAt = fallback;
        }
    }

    public boolean advanceStatus(MessageStatus nextStatus, LocalDateTime occurredAt) {
        if (nextStatus == null || nextStatus == MessageStatus.FAILED) {
            throw new IllegalArgumentException("Receipt status must be SENT, DELIVERED, or READ");
        }
        if (nextStatus == status || status == MessageStatus.READ) {
            return false;
        }
        if (status == MessageStatus.DELIVERED && nextStatus != MessageStatus.READ) {
            return false;
        }
        if (status == MessageStatus.SENT
                && nextStatus != MessageStatus.DELIVERED
                && nextStatus != MessageStatus.READ) {
            return false;
        }

        LocalDateTime transitionAt = occurredAt == null ? LocalDateTime.now() : occurredAt;
        status = nextStatus;
        normalizeTimestamps(transitionAt);
        return true;
    }

    public boolean markDelivered(LocalDateTime deliveredAt) {
        return advanceStatus(MessageStatus.DELIVERED, deliveredAt);
    }

    public boolean markRead(LocalDateTime readAt) {
        return advanceStatus(MessageStatus.READ, readAt);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public void setStatus(MessageStatus status) {
        advanceStatus(status, LocalDateTime.now());
    }

    public LocalDateTime getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(LocalDateTime deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    public void setReadAt(LocalDateTime readAt) {
        this.readAt = readAt;
    }

    public boolean isLegacyBackfilled() {
        return legacyBackfilled;
    }

    public void setLegacyBackfilled(boolean legacyBackfilled) {
        this.legacyBackfilled = legacyBackfilled;
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
