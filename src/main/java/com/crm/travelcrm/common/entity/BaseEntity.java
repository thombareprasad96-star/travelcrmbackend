package com.crm.travelcrm.common.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@SuperBuilder
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    // External-facing ID — never expose internal Long id in APIs
    @UuidGenerator
    @Column(name = "public_id", updatable = false, nullable = false, unique = true)
    private UUID publicId;

    // Who created and last modified — wired via Spring Security AuditorAware
    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private String createdBy;          // stores username/email from SecurityContext

    @LastModifiedBy
    @Column(name = "updated_by")
    private String updatedBy;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Soft delete — replaces boolean active on every entity
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "deleted_by")
    private String deletedBy;

    // Convenience methods
    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void softDelete(String deletedByUser) {
        this.deletedAt  = LocalDateTime.now();
        this.deletedBy  = deletedByUser;
    }

    public void restore() {
        this.deletedAt = null;
        this.deletedBy = null;
    }
}