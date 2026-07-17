package com.crm.travelcrm.platform.notification.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import com.crm.travelcrm.platform.notification.enums.PlatformNotificationStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 * A persisted in-app notification for the platform SuperAdmin — one row per (event, recipient).
 *
 * <p><b>Why this extends {@link BaseEntity} and not {@code BaseTenantEntity}.</b> A SuperAdmin has
 * no tenant. Extending {@code BaseTenantEntity} would bring three things that are all wrong here:
 * a {@code NOT NULL tenant_id}, the Hibernate {@code tenantFilter}, and {@code TenantEntityListener}
 * auto-stamping. The filter is the dangerous one — {@code TenantFilterAspect} returns early when
 * {@code TenantContext} is empty, so the filter would simply not be applied on a platform request,
 * and the "isolation" would be a comment rather than a mechanism.
 *
 * <p><b>{@code tenantId} here is display context, not a scope.</b> It answers "which tenant is this
 * notification about?" — never "who is allowed to read it". Only the SuperAdmin named by
 * {@code recipientSuperAdminId} reads a row, and that predicate is on every query.
 *
 * <p>{@code tenantName} is denormalised on purpose. A notification is a point-in-time record: it
 * should keep saying what was true when it fired, even if the tenant is later renamed or deleted.
 * It also keeps the console feed free of joins.
 *
 * <p><b>{@code recipientSuperAdminId} references {@code super_admins.id}, NOT {@code users.id}.</b>
 * Those are separate {@code IDENTITY} sequences, so the same numeric value is a valid id in both
 * tables. That is precisely why this is a separate table from {@code notifications} — no query can
 * confuse the two namespaces because no query spans them.
 */
@Entity
@Table(
        name = "platform_notifications",
        indexes = {
                // Feed/badge queries filter on recipient + status + soft-delete, newest first.
                @Index(name = "idx_platform_notification_recipient",
                        columnList = "recipient_super_admin_id, status, deleted_at"),
                @Index(name = "idx_platform_notification_created", columnList = "created_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PlatformNotification extends BaseEntity {

    /** Internal reference to super_admins.id — never exposed in API responses. */
    // No DB-level FK — cross-aggregate reference, enforced at the application layer (matches the
    // convention Notification.recipientUserId already follows).
    @Column(name = "recipient_super_admin_id", nullable = false, updatable = false)
    private Long recipientSuperAdminId;

    /** Free-form string constant — see PlatformNotificationType. */
    @Column(name = "type", nullable = false, length = 100, updatable = false)
    private String type;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private PlatformNotificationStatus status = PlatformNotificationStatus.UNREAD;

    /**
     * Entity type that triggered this notification — the console's deep-link discriminator.
     *
     * <p>A free-form String, deliberately. The tenant module routes this through
     * {@code NotificationReferenceType.fromString()}, which returns null for any value missing from
     * the enum — silently dropping the discriminator while leaving referencePublicId populated, so
     * the client gets an id it cannot route. Storing the String verbatim cannot fail that way.
     */
    @Column(name = "reference_type", length = 50, updatable = false)
    private String referenceType;

    /** Public UUID of the source entity — safe to return in API responses. */
    @Column(name = "reference_public_id", updatable = false)
    private UUID referencePublicId;

    // ── Tenant context (display only — NOT an isolation boundary; see class javadoc) ──────────

    @Column(name = "tenant_id", updatable = false)
    private Long tenantId;

    @Column(name = "tenant_name", length = 200, updatable = false)
    private String tenantName;

    @Column(name = "tenant_public_id", updatable = false)
    private UUID tenantPublicId;

    @Column(name = "read_at")
    private Instant readAt;

    public void markRead() {
        this.status = PlatformNotificationStatus.READ;
        this.readAt = Instant.now();
    }
}