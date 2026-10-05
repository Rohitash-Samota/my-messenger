package com.rohitsamota.my_messenger.entity;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import com.rohitsamota.my_messenger.enums.MessageStatus;
import com.rohitsamota.my_messenger.enums.MessageType;

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
        name = "messages",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_messages_sender_client_message",
                columnNames = {"user_id", "client_message_id"}),
        indexes = {
                @Index(name = "idx_messages_conversion_id_id", columnList = "conversion_id,id"),
                @Index(name = "idx_messages_client_message_id", columnList = "client_message_id"),
                @Index(
                        name = "idx_messages_conversion_media_deleted",
                        columnList = "conversion_id,media_id,deleted_at")
        })
public class Message {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

    @Column(name="user_id", nullable=false)
    private Long userId;

    @Column(name="parent_message_id", nullable=true)
    private Long parentMessageId;

    @Column(name = "conversion_id", nullable=true)
    private Long conversionId;

    @Column(name = "client_message_id", nullable = false, length = 64)
    private String clientMessageId;

    @Column(name = "content", nullable=true, length=1000)
    private String content;

    @Column(name = "original_content_sha256", length = 64, updatable = false)
    private String originalContentSha256;

    @Column(name = "media_id", length = 36, updatable = false)
    private String mediaId;

    @Enumerated(EnumType.STRING)
    @Column(name="message_type", nullable=false, length=20)
    private MessageType messageType = MessageType.TEXT;

    @Enumerated(EnumType.STRING)
    @Column(name="status", nullable=false, length = 20)
    private MessageStatus status = MessageStatus.SENT;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "edited_at")
    private LocalDateTime editedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

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
        if (status == null) {
            status = MessageStatus.SENT;
        }
        if (messageType == null) {
            messageType = MessageType.TEXT;
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

    public Long getSenderId() {
        return userId;
    }

    public void setSenderId(Long senderId) {
        this.userId = senderId;
    }

    public Long getParentMessageId() {
        return parentMessageId;
    }

    public void setParentMessageId(Long parentMessageId) {
        this.parentMessageId = parentMessageId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getOriginalContentSha256() {
        return originalContentSha256;
    }

    public void setOriginalContentSha256(String originalContentSha256) {
        if (originalContentSha256 == null
                || !originalContentSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "originalContentSha256 must be a lowercase SHA-256 value");
        }
        if (this.originalContentSha256 != null
                && !Objects.equals(this.originalContentSha256, originalContentSha256)) {
            throw new IllegalStateException("The original content fingerprint is immutable");
        }
        this.originalContentSha256 = originalContentSha256;
    }

    public String getMediaId() {
        return mediaId;
    }

    public void setMediaId(String mediaId) {
        if (mediaId == null) {
            if (this.mediaId != null) {
                throw new IllegalStateException("The media reference is immutable");
            }
            return;
        }
        String canonicalMediaId;
        try {
            canonicalMediaId = UUID.fromString(mediaId).toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("mediaId must be a canonical UUID", exception);
        }
        if (!canonicalMediaId.equals(mediaId)) {
            throw new IllegalArgumentException("mediaId must be a canonical UUID");
        }
        if (this.mediaId != null && !Objects.equals(this.mediaId, canonicalMediaId)) {
            throw new IllegalStateException("The media reference is immutable");
        }
        this.mediaId = canonicalMediaId;
    }

    public void editContent(String content, LocalDateTime editedAt) {
        if (deletedAt != null) {
            throw new IllegalStateException("Deleted messages cannot be edited");
        }
        this.content = content;
        this.editedAt = editedAt == null ? LocalDateTime.now() : editedAt;
    }

    public boolean softDelete(LocalDateTime deletedAt) {
        if (this.deletedAt != null) {
            return false;
        }
        this.content = null;
        this.deletedAt = deletedAt == null ? LocalDateTime.now() : deletedAt;
        return true;
    }

    public MessageType getMessageType() {
        return messageType;
    }

    public void setMessageType(MessageType messageType) {
        this.messageType = messageType;
    }

    public Long getConversionId() {
        return conversionId;
    }

    public void setConversionId(Long conversionId) {
        this.conversionId = conversionId;
    }

    public String getClientMessageId() {
        return clientMessageId;
    }

    public void setClientMessageId(String clientMessageId) {
        this.clientMessageId = clientMessageId;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public void setStatus(MessageStatus status) {
        this.status = status;
    }

    public boolean advanceStatus(MessageStatus nextStatus) {
        if (nextStatus == null || nextStatus == status) {
            return false;
        }
        if (status == MessageStatus.READ) {
            return false;
        }
        if (status == MessageStatus.DELIVERED && nextStatus != MessageStatus.READ) {
            return false;
        }
        if (status == MessageStatus.FAILED
                && nextStatus != MessageStatus.SENT
                && nextStatus != MessageStatus.DELIVERED
                && nextStatus != MessageStatus.READ) {
            return false;
        }
        status = nextStatus;
        return true;
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

    public LocalDateTime getEditedAt() {
        return editedAt;
    }

    public void setEditedAt(LocalDateTime editedAt) {
        this.editedAt = editedAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
    
}
