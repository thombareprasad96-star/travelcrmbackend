package com.crm.travelcrm.platform.audit.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * A platform-level audit-trail entry: which SuperAdmin did what, to which tenant/target,
 * when, and from where.
 *
 * <p><b>Platform-level, NOT tenant-scoped.</b> It extends {@link BaseEntity} (not
 * {@code BaseTenantEntity}) so it is never caught by the Hibernate tenant {@code @Filter}
 * and carries no {@code tenant_id} of its own — a single global god-mode ledger. The tenant
 * touched by an action (if any) is captured explicitly in {@link #targetTenantId} /
 * {@link #targetTenantCode}. This is the deliberate counterpart to the tenant-scoped
 * {@code ActivityLog}, which structurally cannot record actions that have no tenant.
 *
 * <p>The acting SuperAdmin is a logical FK ({@link #superAdminId} → {@code super_admins.id},
 * no DB constraint) plus an {@link #actorEmail} snapshot, so the ledger survives a rename and
 * never joins back at read time. {@code createdAt} (from {@link BaseEntity}) is the event
 * time; {@code publicId} is the external identifier. Rows are append-only — never soft-deleted.
 */
@Entity
@Table(name = "platform_audit_logs", indexes = {
        @Index(name = "idx_pal_action",        columnList = "action"),
        @Index(name = "idx_pal_actor",         columnList = "super_admin_id"),
        @Index(name = "idx_pal_target_tenant", columnList = "target_tenant_id"),
        @Index(name = "idx_pal_created_at",     columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PlatformAuditLog extends BaseEntity {

    /** Logical FK to {@code super_admins.id}. Null only for a failed login with an unknown email. */
    @Column(name = "super_admin_id")
    private Long superAdminId;

    /** Snapshot of the acting SuperAdmin's email (or the attempted email on a failed login). */
    @Column(name = "actor_email", length = 150)
    private String actorEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 40)
    private PlatformAuditAction action;

    /** The tenant this action touched (logical FK to {@code tenants.id}); null = platform-wide. */
    @Column(name = "target_tenant_id")
    private Long targetTenantId;

    /** Snapshot of the target tenant's organization code (readable in the log without a join). */
    @Column(name = "target_tenant_code", length = 50)
    private String targetTenantCode;

    /** Generic target descriptor, e.g. {@code "TENANT"}, {@code "USER"} (nullable). */
    @Column(name = "target_type", length = 40)
    private String targetType;

    /** The target's external {@code publicId} (UUID) when applicable — never the internal Long PK. */
    @Column(name = "target_public_id")
    private UUID targetPublicId;

    /** Whether the action succeeded (false for failed logins / blocked operations). */
    @Builder.Default
    @Column(name = "success", nullable = false)
    private boolean success = true;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Optional structured detail (e.g. before/after JSON). Free-form. */
    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    /** IPv4 or IPv6 (max 45 chars). */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;
}
