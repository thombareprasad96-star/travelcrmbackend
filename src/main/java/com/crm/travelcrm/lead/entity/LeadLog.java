package com.crm.travelcrm.lead.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.lead.enums.LeadStage;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;

/**
 * One activity-log entry attached to a {@link Lead} (a call note, status update, etc.).
 *
 * <p>Tenant-scoped via {@link BaseTenantEntity}; written through
 * {@code POST /api/leads/{publicId}/logs}. The owning lead is always resolved under tenant +
 * row-level scope (LeadAccessGuard) before a log is created, so a log can never attach to a lead
 * the caller cannot see. The log's author/time come from the audit fields on the base class
 * ({@code createdBy}/{@code createdAt}); {@code addedByUserId}/{@code addedByName} additionally
 * snapshot the author for cheap display without a user join.</p>
 */
@Entity
@Table(name = "lead_logs", indexes = {
        @Index(name = "idx_lead_log_lead", columnList = "lead_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class LeadLog extends BaseTenantEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lead_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_lead_log_lead"))
    private Lead lead;

    @Column(name = "comment", nullable = false, columnDefinition = "TEXT")
    private String comment;

    /** Snapshot of the lead's stage at the moment the log was written. */
    @Enumerated(EnumType.STRING)
    @Column(name = "stage_snapshot", length = 50)
    private LeadStage stageSnapshot;

    /** Optional follow-up date the user noted (also drives the reminder when requested). */
    @Column(name = "follow_up_date")
    private LocalDate followUpDate;

    /** Logical FK to {@code User.id} (Long) of whoever added the log — no DB-level FK. */
    @Column(name = "added_by_user_id")
    private Long addedByUserId;

    /** Denormalized display name of the author, so list views need no user join. */
    @Column(name = "added_by_name", length = 150)
    private String addedByName;
}