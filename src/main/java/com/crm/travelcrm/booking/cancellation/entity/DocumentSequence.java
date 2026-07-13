package com.crm.travelcrm.booking.cancellation.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Per-tenant, per-series running counter backing document numbers (CN-YY-NNNN, DBN-YY-NNNN,
 * RFV-YY-NNNN). Mirrors {@code BookingSequence}: a plain {@code @Entity} (NOT a tenant entity) so the
 * generator can read it directly under a pessimistic lock without the Hibernate tenant filter
 * interfering. One row per (tenant, series), enforced by the UNIQUE constraint.
 */
@Entity
@Table(
        name = "document_sequences",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_document_sequence_tenant_series", columnNames = {"tenant_id", "series_code"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    /** Series prefix, e.g. "CN", "DBN", "RFV". */
    @Column(name = "series_code", nullable = false, length = 10, updatable = false)
    private String seriesCode;

    @Builder.Default
    @Column(name = "last_value", nullable = false)
    private Long lastValue = 0L;
}