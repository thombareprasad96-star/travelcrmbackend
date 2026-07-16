package com.crm.travelcrm.lead.assignment.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Per-tenant round-robin cursor for lead assignment. Exactly one row per tenant;
 * {@code lastRecommendedUserId} is the internal id of the user the load-based/round-robin strategy
 * last handed out. Persisting it here is what makes the rotation survive a server restart.
 *
 * <p>Deliberately a plain {@code @Entity} (NOT a {@link com.crm.travelcrm.common.entity.BaseTenantEntity}),
 * exactly like {@code BookingSequence}: it carries no {@code publicId}/audit/soft-delete and must NOT
 * pick up the Hibernate {@code tenantFilter} — the assignment service reads it directly by
 * {@code tenantId} under a pessimistic write lock, and the filter would interfere with that locked
 * read. Tenant isolation is still guaranteed because every lookup is explicitly keyed by
 * {@code tenantId}, which also carries a UNIQUE constraint (one cursor per tenant).
 */
@Entity
@Table(
        name = "lead_assignment_pointers",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_lead_assignment_pointer_tenant",
                columnNames = "tenant_id"
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadAssignmentPointer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    /** Internal id of the user the cursor last handed out for this tenant; null before the first advance. */
    @Column(name = "last_recommended_user_id")
    private Long lastRecommendedUserId;
}