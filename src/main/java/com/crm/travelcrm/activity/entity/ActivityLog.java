package com.crm.travelcrm.activity.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * An audit-trail entry: who did what, when, and from where.
 *
 * <p>Tenant-scoped via {@link BaseTenantEntity} (so the Activity Reports always read only the
 * caller's tenant). The acting user is referenced by a logical FK ({@link #actingUserId} →
 * {@code users.id}, no DB constraint, same pattern as {@code Reminder.assignToUserId}); the
 * user's display name / email / role-label are <b>denormalised snapshots</b> taken at write time
 * so the high-volume log list never joins back to {@code users} (and survives a user rename/delete).
 *
 * <p>The external identifier is {@code publicId} (UUID) from {@link com.crm.travelcrm.common.entity.BaseEntity};
 * the internal {@code Long id} is never exposed. {@code createdAt} (audit field) is the event timestamp.
 *
 * <p>Rows are written best-effort by {@code ActivityLogRecorder} — from the login path and from
 * {@code ActivityAuditAspect} (controller write methods). A logging failure must never break the
 * business request.
 */
@Entity
@Table(name = "activity_logs", indexes = {
        @Index(name = "idx_activity_tenant",     columnList = "tenant_id"),
        @Index(name = "idx_activity_created_at", columnList = "created_at"),
        @Index(name = "idx_activity_action",     columnList = "action"),
        @Index(name = "idx_activity_user",       columnList = "user_id"),
        @Index(name = "idx_activity_user_type",  columnList = "user_type")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class ActivityLog extends BaseTenantEntity {

    /** Logical FK to {@code users.id} (Long). No DB-level FK — enforced at the application layer. */
    @Column(name = "user_id")
    private Long actingUserId;

    /** Denormalised snapshot of the acting user's full name — avoids an N+1 join in list views. */
    @Column(name = "user_name", length = 150)
    private String userName;

    /** Denormalised snapshot of the acting user's email — the report derives the {@code @username} from this. */
    @Column(name = "user_email", length = 150)
    private String userEmail;

    /** Display label for the acting user's role: Admin | Manager | Staff | Agent | Accountant | User. */
    @Column(name = "user_type", length = 20)
    private String userType;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    private ActivityAction action;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** IPv4 or IPv6 (max 45 chars). */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;
}