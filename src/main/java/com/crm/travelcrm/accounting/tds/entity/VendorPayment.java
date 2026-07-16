package com.crm.travelcrm.accounting.tds.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One disbursement made against a {@link VendorBill} — a positive amount forming a per-payment ledger
 * (clones the {@code BookingPayment} shape: positive magnitude, free-text method, optional
 * idempotency key). The parent bill's {@code amountPaid} is kept in step by the service on each add.
 */
@Entity
@Table(
        name = "vendor_payments",
        indexes = {
                @Index(name = "idx_vpay_tenant",  columnList = "tenant_id"),
                @Index(name = "idx_vpay_bill",    columnList = "tenant_id,vendor_bill_id"),
                @Index(name = "idx_vpay_deleted", columnList = "tenant_id,deleted_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class VendorPayment extends BaseTenantEntity {

    @Column(name = "vendor_bill_id", nullable = false)
    private Long vendorBillId;

    @Column(name = "vendor_id", nullable = false)
    private Long vendorId;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /** TDS withheld on this disbursement (informational; the bill holds the authoritative TDS). */
    @Builder.Default
    @Column(name = "tds_withheld", nullable = false, precision = 14, scale = 2)
    private BigDecimal tdsWithheld = BigDecimal.ZERO;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Column(name = "payment_method", length = 40)
    private String paymentMethod;

    @Column(name = "reference", length = 120)
    private String reference;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** Optional idempotency token — a partial-unique index collapses a resubmit onto the first row. */
    @Column(name = "idempotency_key", length = 80)
    private String idempotencyKey;
}