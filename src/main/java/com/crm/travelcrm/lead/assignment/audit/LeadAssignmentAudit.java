package com.crm.travelcrm.lead.assignment.audit;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.lead.assignment.strategy.AssignmentStrategyType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * One audit row per lead creation, capturing how the lead's owner was decided:
 * who created it, what the strategy recommended, who it was actually assigned to, which strategy was
 * used, and whether a human overrode the recommendation.
 *
 * <p>Tenant-scoped via {@link BaseTenantEntity}. User references are logical FKs to {@code users.id}
 * (no DB constraint — house pattern, same as {@code ActivityLog.actingUserId}); the display names are
 * denormalised snapshots taken at write time so the audit list never joins back to {@code users} and
 * survives a rename/delete. The external id is {@code publicId}; {@code createdAt} (audit field) is
 * the event timestamp.
 */
@Entity
@Table(name = "lead_assignment_audits", indexes = {
        @Index(name = "idx_lead_assignment_audit_tenant",   columnList = "tenant_id"),
        @Index(name = "idx_lead_assignment_audit_lead",     columnList = "lead_id"),
        @Index(name = "idx_lead_assignment_audit_created",  columnList = "created_at"),
        @Index(name = "idx_lead_assignment_audit_strategy", columnList = "strategy_used")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class LeadAssignmentAudit extends BaseTenantEntity {

    /** Logical FK to {@code leads.id} (Long). No DB-level FK — enforced at the application layer. */
    @Column(name = "lead_id", nullable = false)
    private Long leadId;

    /** The lead's public UUID — safe to expose in a future audit-feed response. */
    @Column(name = "lead_public_id", nullable = false)
    private UUID leadPublicId;

    /** Logical FK to {@code users.id} — the user who created the lead. */
    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_by_name", length = 150)
    private String createdByName;

    /** What the strategy recommended (logical FK to {@code users.id}); null if nothing could be recommended. */
    @Column(name = "recommended_user_id")
    private Long recommendedUserId;

    @Column(name = "recommended_user_name", length = 150)
    private String recommendedUserName;

    /** Who the lead was actually assigned to (logical FK to {@code users.id}). */
    @Column(name = "final_assigned_user_id", nullable = false)
    private Long finalAssignedUserId;

    @Column(name = "final_assigned_user_name", length = 150)
    private String finalAssignedUserName;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_used", nullable = false, length = 20)
    private AssignmentStrategyType strategyUsed;

    /** True when the final assignee differs from the recommendation (a human overrode the algorithm). */
    @Column(name = "manual_override", nullable = false)
    private boolean manualOverride;
}