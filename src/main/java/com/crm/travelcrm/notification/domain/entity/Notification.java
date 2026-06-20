package com.crm.travelcrm.notification.domain.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.notification.domain.enums.NotificationReferenceType;
import com.crm.travelcrm.notification.domain.enums.NotificationStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted in-app notification — one row per (event, recipient).
 *
 * <p>Tenant isolation: inherits Hibernate {@code tenantFilter} from
 * {@link BaseTenantEntity}. User-level isolation enforced by all queries
 * filtering on {@code recipientUserId = currentUser.id}.
 *
 * <p>{@code type} is stored as a free-form VARCHAR so new event types from any
 * business module are stored without modifying this entity.
 */
@Entity
@Table(
        name = "notifications",
        indexes = {
                // Feed/badge queries filter on recipient + status + soft-delete, newest first.
                @Index(name = "idx_notification_recipient",
                        columnList = "recipient_user_id, status, deleted_at"),
                @Index(name = "idx_notification_created", columnList = "created_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Notification extends BaseTenantEntity {

    /** Internal reference to users.id — never exposed in API responses. */
    // No DB-level FK — cross-aggregate reference to users.id, enforced at the application layer.
    @Column(name = "recipient_user_id", nullable = false, updatable = false)
    private Long recipientUserId;

    /** Free-form string constant from the publishing module. */
    @Column(name = "type", nullable = false, length = 100, updatable = false)
    private String type;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private NotificationStatus status = NotificationStatus.UNREAD;

    /** Entity type that triggered this notification — discriminator for FE routing. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", length = 50, updatable = false)
    private NotificationReferenceType referenceType;

    /** Public UUID of the source entity — safe to return in API responses. */
    @Column(name = "reference_public_id", updatable = false)
    private UUID referencePublicId;

    @Column(name = "read_at")
    private Instant readAt;

    public void markRead() {
        this.status = NotificationStatus.READ;
        this.readAt = Instant.now();
    }
}