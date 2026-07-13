package com.crm.travelcrm.platform.billing.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import com.crm.travelcrm.platform.billing.enums.BillingStatus;
import com.crm.travelcrm.tenent.enums.TenantPlan;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A platform invoice raised by the SuperAdmin against a tenant (SaaS → tenant billing).
 * <b>Platform-level</b> (extends {@link BaseEntity}, not {@code BaseTenantEntity}) — it is not part
 * of any tenant's data and must never be caught by the tenant {@code @Filter}.
 *
 * <p>The tenant is referenced by the logical FK {@code tenantId}, but {@code tenantCode} /
 * {@code tenantName} / {@code plan} are <b>snapshotted at issue time</b> so an invoice stays
 * accurate and readable even if the tenant is later renamed, re-planned, or soft-deleted.
 */
@Entity
@Table(name = "billing_records",
        uniqueConstraints = @UniqueConstraint(name = "uq_billing_invoice_number", columnNames = "invoice_number"),
        indexes = {
                @Index(name = "idx_billing_tenant", columnList = "tenant_id"),
                @Index(name = "idx_billing_status", columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class BillingRecord extends BaseEntity {

    /** Logical FK to {@code tenants.id} (no JPA association — platform entity). */
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "tenant_code", length = 50)
    private String tenantCode;

    @Column(name = "tenant_name", length = 150)
    private String tenantName;

    @Column(name = "invoice_number", nullable = false, length = 30)
    private String invoiceNumber;

    /** Plan snapshotted at issue time. */
    @Enumerated(EnumType.STRING)
    @Column(name = "plan", length = 20)
    private TenantPlan plan;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", length = 8)
    @Builder.Default
    private String currency = "INR";

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "paid_date")
    private LocalDate paidDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private BillingStatus status = BillingStatus.UNPAID;

    @Column(name = "notes", length = 500)
    private String notes;

    /**
     * Pay-first plan upgrade marker: when non-null, settling this invoice (webhook capture or an offline
     * mark-paid) applies this plan's entitlements to the tenant. Null for ordinary invoices. New column —
     * Hibernate ddl-auto adds it; it holds the full {@link TenantPlan} value set so no constraint refresh.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "upgrade_to_plan", length = 20)
    private TenantPlan upgradeToPlan;

    /** Derived: an unpaid invoice whose due date has passed. Not persisted. */
    @Transient
    public boolean isOverdue() {
        return status == BillingStatus.UNPAID
                && dueDate != null
                && dueDate.isBefore(LocalDate.now());
    }
}