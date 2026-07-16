package com.crm.travelcrm.accounting.invoice.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Per-tenant, per-series, per-<b>financial-year</b> running counter backing GST invoice numbers
 * (TI/2627/00001, BOS/2627/00001, …). Mirrors {@code DocumentSequence}: a plain {@code @Entity} (NOT
 * a tenant entity) so the generator can read it under a pessimistic lock without the Hibernate tenant
 * filter interfering with {@code SELECT … FOR UPDATE}.
 *
 * <p><b>Key difference from the cancellation-document counter:</b> the UNIQUE key includes
 * {@code financial_year}, and {@code lastValue} therefore restarts at 0 in each new FY — exactly what
 * GST Rule 46(b) requires (a unique consecutive serial per financial year).
 */
@Entity
@Table(
        name = "invoice_sequences",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_invoice_sequence_tenant_series_fy",
                columnNames = {"tenant_id", "series_code", "financial_year"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    /** Series prefix, e.g. "TI", "BOS", "INV", "GCN", "GDN". */
    @Column(name = "series_code", nullable = false, length = 12, updatable = false)
    private String seriesCode;

    /** Financial year label, e.g. "2026-27". Partitions the counter so it resets each FY. */
    @Column(name = "financial_year", nullable = false, length = 9, updatable = false)
    private String financialYear;

    @Builder.Default
    @Column(name = "last_value", nullable = false)
    private Long lastValue = 0L;
}