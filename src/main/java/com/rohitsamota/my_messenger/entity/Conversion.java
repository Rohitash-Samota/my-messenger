package com.rohitsamota.my_messenger.entity;

import java.time.LocalDateTime;

import com.rohitsamota.my_messenger.enums.ConversionType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "conversions")
public class Conversion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

    @Column(name="user_id",nullable=false)
    private Long userId;

    @Column(name="client_id",nullable=false)
    private Long clientId;

    @Column(name = "conversion_type", nullable=false)
    private ConversionType conversionType;

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

    public void setIsPin(boolean isPin) {
        this.isPin = isPin;
    }

    public boolean isIsArchive() {
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
}
