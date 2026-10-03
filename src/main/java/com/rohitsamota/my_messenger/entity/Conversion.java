package com.rohitsamota.my_messenger.entity;

import java.time.LocalDateTime;

import com.rohitsamota.my_messenger.enums.ConversionType;

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
        name = "conversions",
        indexes = {
                @Index(name = "idx_conversions_last_activity", columnList = "last_activity_at,id"),
                @Index(name = "idx_conversions_last_message_id", columnList = "last_message_id")
        })
public class Conversion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

    @Column(name="user_id",nullable=false)
    private Long userId;

    @Column(name="client_id",nullable=false)
    private Long clientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "conversion_type", nullable=false, length = 20)
    private ConversionType conversionType = ConversionType.INDIVIDUAL;

    @Column(name = "is_pin", nullable = false, columnDefinition = "boolean default false")
    private boolean isPin;

    @Column(name="is_archive", nullable=false, columnDefinition="boolean default false")
    private boolean isArchive;

    @Column(name="deleted_at",nullable=true)
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_message_id")
    private Long lastMessageId;

    @Column(name = "last_activity_at", nullable = false)
    private LocalDateTime lastActivityAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    void setCreationTimestamps() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (lastActivityAt == null) {
            lastActivityAt = now;
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

    public Long getClientId() {
        return clientId;
    }

    public void setClientId(Long clientId) {
        this.clientId = clientId;
    }

    public boolean isIsPin() {
        return isPin;
    }

    public boolean isPinned() {
        return isPin;
    }

    public void setIsPin(boolean isPin) {
        this.isPin = isPin;
    }

    public boolean isIsArchive() {
        return isArchive;
    }

    public boolean isArchived() {
        return isArchive;
    }

    public void setIsArchive(boolean isArchive) {
        this.isArchive = isArchive;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public ConversionType getConversionType() {
        return conversionType;
    }

    public void setConversionType(ConversionType conversionType) {
        this.conversionType = conversionType;
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

    public Long getLastMessageId() {
        return lastMessageId;
    }

    public void setLastMessageId(Long lastMessageId) {
        this.lastMessageId = lastMessageId;
    }

    public LocalDateTime getLastActivityAt() {
        return lastActivityAt;
    }

    public void setLastActivityAt(LocalDateTime lastActivityAt) {
        this.lastActivityAt = lastActivityAt;
    }

    public void recordActivity(Long messageId, LocalDateTime activityAt) {
        this.lastMessageId = messageId;
        this.lastActivityAt = activityAt == null ? LocalDateTime.now() : activityAt;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
