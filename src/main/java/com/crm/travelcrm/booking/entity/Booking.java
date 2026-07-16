package com.crm.travelcrm.booking.entity;

import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.enums.PaymentStatus;
import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.common.entity.Ownable;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Audited
@Entity
@Table(
        name = "bookings",
        indexes = {
                @Index(name = "idx_booking_tenant",      columnList = "tenant_id"),
                @Index(name = "idx_booking_code",        columnList = "tenant_id,booking_code"),
                @Index(name = "idx_booking_customer",    columnList = "customer_id"),
                @Index(name = "idx_booking_status",      columnList = "tenant_id,status"),
                @Index(name = "idx_booking_travel_date", columnList = "tenant_id,travel_date"),
                @Index(name = "idx_booking_deleted",     columnList = "tenant_id,deleted_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Booking extends BaseTenantEntity implements Ownable {

    // Row-level owner: the sub-agent / user who created this booking (per-user data scoping).
    // Nullable — pre-existing & system rows have none. Stamped on create by OwnershipEntityListener;
    // read by BookingAccessGuard + list filters (Phase 2). Distinct from created_by (audit email).
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    // ───────────────── Identity ─────────────────

    @Column(name = "booking_code", nullable = false, length = 20)
    private String bookingCode;

    // ───────────────── Concurrency ─────────────────
    // Optimistic lock. Every financial mutation (add/delete payment, PATCH /payment) is a
    // read-modify-write on paidAmount with no DB-level serialization; without this, two concurrent
    // receipts (or a double-click) both read the same paidAmount and one increment is silently lost,
    // and the overpayment guard — computed from the stale read — can be jointly bypassed. With
    // @Version the losing transaction fails with OptimisticLockException (mapped to 409 by
    // GlobalExceptionHandler) instead of clobbering. Not audited — version bumps are not business data.
    @NotAudited
    @Version
    @Column(name = "version")
    private Long version;

    // ───────────────── Relationships ─────────────────

    // No DB-level FK — cross-aggregate reference to customers.id, enforced at the application layer.
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "customer_name_snapshot", nullable = false, length = 255)
    private String customerNameSnapshot;

    // No DB-level FK — cross-aggregate reference to destination_master.destination_id, enforced at the application layer.
    @Column(name = "destination_id")
    private Long destinationId;

    @Column(name = "destination_snapshot", nullable = false, length = 255)
    private String destinationSnapshot;

    // No DB-level FK — cross-aggregate reference to leads.id, enforced at the application layer.
    @Column(name = "lead_id")
    private Long leadId;

    // ── Conversion traceability (Lead → Quotation → Booking) ──────────────────
    // Set only when this booking was produced by converting a lead. They store the
    // source lead/quotation by their publicId (UUID) — never the internal Long id — so
    // Reports can trace a booking back to the lead and the quotation it came from.
    @Column(name = "source_lead_public_id")
    private java.util.UUID sourceLeadPublicId;

    @Column(name = "source_quotation_public_id")
    private java.util.UUID sourceQuotationPublicId;

    // ── Cancellation-policy pin (anti-retroactivity) ──────────────────────────
    // Resolved and stamped ONCE at create/convert: the exact CancellationPolicy version that
    // governs this booking's cancellation charges. Because policy versions are immutable, editing
    // the tenant's policy tomorrow produces a NEW version and can never change how this booking —
    // made today — is charged. Null only on legacy rows created before this feature; the cancel
    // flow then resolves the company default as-of bookingDate and pins it on first cancel.
    @Column(name = "cancellation_policy_public_id")
    private java.util.UUID cancellationPolicyPublicId;

    @Column(name = "cancellation_policy_version")
    private Integer cancellationPolicyVersion;

    // ───────────────── Financials ─────────────────

    @Builder.Default
    @Column(name = "customer_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal customerAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "vendor_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal vendorCost = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "gst", nullable = false, precision = 12, scale = 2)
    private BigDecimal gst = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "tcs", nullable = false, precision = 12, scale = 2)
    private BigDecimal tcs = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "total_payable", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalPayable = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "paid_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    // Gross amount refunded to the customer (money OUT), accumulated by the refund flow. This is the
    // single, @Version-guarded source of refund truth — refund ledger rows (entryType REFUND) sum
    // into it and it is deliberately SEPARATE from paidAmount, which stays the historical gross
    // received so pendingAmount, the payment status and existing receipts aggregates are unaffected.
    @Builder.Default
    @Column(name = "refunded_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "net_profit", nullable = false, precision = 12, scale = 2)
    private BigDecimal netProfit = BigDecimal.ZERO;

    // ───────────────── Status ─────────────────

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BookingStatus status = BookingStatus.PENDING;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

    // ───────────────── Dates ─────────────────

    @Column(name = "booking_date", nullable = false)
    private LocalDate bookingDate;

    @Column(name = "travel_date", nullable = false)
    private LocalDate travelDate;

    // ───────────────── Services ─────────────────

    @NotAudited
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "booking_services",
            joinColumns = @JoinColumn(name = "booking_id")
    )
    @Column(name = "service_name", length = 100)
    @Builder.Default
    private List<String> services = new ArrayList<>();

    // ───────────────── Soft Delete ─────────────────
    // Soft-delete is tracked solely by BaseEntity.deletedAt / deletedBy.
    // (The previous standalone `active` boolean was removed — a single source of truth.)

    // ───────────────── Derived Fields ─────────────────

    @Transient
    public BigDecimal getPendingAmount() {
        if (totalPayable == null || paidAmount == null) return BigDecimal.ZERO;
        return totalPayable.subtract(paidAmount);
    }
}