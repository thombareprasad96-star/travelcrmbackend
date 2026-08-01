package com.crm.travelcrm.lead.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Per-tenant running counter that backs the human-readable lead reference
 * ({@code LD-YY-NNNN}). Exactly one row per tenant; {@code lastValue} is the last
 * number handed out.
 *
 * <p>Deliberately a plain {@code @Entity} (NOT a {@link com.crm.travelcrm.common.entity.BaseTenantEntity}),
 * for the same reasons as {@code BookingSequence}: it carries no {@code publicId}/audit/soft-delete
 * and must NOT pick up the Hibernate {@code tenantFilter} — the generator reads it directly by
 * {@code tenantId} under a pessimistic lock, and the filter would interfere with that locked read.
 * Tenant isolation still holds because every lookup is explicitly keyed by {@code tenantId}, which
 * also carries a UNIQUE constraint (one counter per tenant).</p>
 */
@Entity
@Table(
        name = "lead_sequences",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_lead_sequence_tenant",
                columnNames = "tenant_id"
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    /** The highest reference number issued so far for this tenant (next = lastValue + 1). */
    @Builder.Default
    @Column(name = "last_value", nullable = false)
    private Long lastValue = 0L;
}
