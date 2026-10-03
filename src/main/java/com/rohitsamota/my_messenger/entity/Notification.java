package com.rohitsamota.my_messenger.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "notifications",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notifications_event_recipient",
                columnNames = {"event_id", "user_id"}),
        indexes = @Index(
                name = "idx_notifications_user_id_id",
                columnList = "user_id,id"))
public class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "conversion_id", nullable = false)
    private Long conversionId;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, length = 500)
    private String body;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Notification() {
    }

    public Notification(
            UUID eventId,
            Long userId,
            Long conversionId,
            Long messageId,
            String title,
            String body) {
        this.eventId = eventId.toString();
        this.userId = userId;
        this.conversionId = conversionId;
        this.messageId = messageId;
        this.title = title;
        this.body = body == null ? "" : body;
    }

    @PrePersist
    void setCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public void markRead() {
        if (readAt == null) {
            readAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return UUID.fromString(eventId);
    }

    public Long getUserId() {
        return userId;
    }

    public Long getConversionId() {
        return conversionId;
    }

    public Long getMessageId() {
        return messageId;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
