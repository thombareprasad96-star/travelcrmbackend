package com.crm.travelcrm.booking.cancellation.entity;

import com.crm.travelcrm.booking.cancellation.enums.CancelScope;
import com.crm.travelcrm.booking.cancellation.enums.DeductionType;
import com.crm.travelcrm.booking.cancellation.enums.RefundStatus;
import com.crm.travelcrm.booking.enums.CancelAction;
import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The immutable financial record of a booking cancellation — one row per booking (a cancellation is
 * terminal and one-time). This is the authoritative, auditor-facing snapshot: it freezes exactly what
 * policy version governed the cancel, what the system computed, what (if anything) was overridden and
 * by whom, the full retained/refund breakdown, and the agency P&amp;L impact. {@code @Audited} (Envers)
 * so any later touch is itself versioned.
 *
 * <p><b>Idempotency.</b> The {@code booking_id} UNIQUE constraint is the real double-cancel guard:
 * two concurrent cancels can pass the in-memory status pre-check, but only one row can insert — the
 * loser fails on the constraint and its whole transaction (including the minted document number)
 * rolls back.
 *
 * <p><b>Single source of refund truth.</b> The amount actually disbursed lives on the version-guarded
 * {@code Booking.refundedAmount}; {@link #refundStatus} here is only the human-readable label, updated
 * in the same transaction as that counter so the two can never diverge.
 */
@Audited
@Entity
@Table(
        name = "booking_cancellations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_booking_cancellation_booking", columnNames = "booking_id"),
        indexes = {
                @Index(name = "idx_bkcancel_tenant",  columnList = "tenant_id"),
                @Index(name = "idx_bkcancel_refund",  columnList = "tenant_id,refund_status"),
                @Index(name = "idx_bkcancel_deleted", columnList = "tenant_id,deleted_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class BookingCancellation extends BaseTenantEntity {

    /** Owning booking — logical FK to {@code bookings.id}, UNIQUE (1:1). */
    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    /** Snapshot so documents/reports need no join. */
    @Column(name = "booking_code", length = 20)
    private String bookingCode;

    // ── Who / when / why ──────────────────────────────────────────────────────
    @Column(name = "cancelled_by_user_id")
    private Long cancelledByUserId;

    @Column(name = "cancelled_by_email", length = 255)
    private String cancelledByEmail;

    @Column(name = "cancelled_at", nullable = false)
    private LocalDateTime cancelledAt;

    /** The effective cancellation date used for the day-count (usually today). */
    @Column(name = "cancel_date", nullable = false)
    private LocalDate cancelDate;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_scope", nullable = false, length = 10)
    private CancelScope cancelScope = CancelScope.FULL;

    /** What happened to the associated lead (reused from the pre-existing cancel flow). */
    @Enumerated(EnumType.STRING)
    @Column(name = "lead_action", length = 30)
    private CancelAction leadAction;

    // ── Governing policy (provenance) ─────────────────────────────────────────
    @Column(name = "policy_public_id")
    private UUID policyPublicId;

    @Column(name = "policy_version")
    private Integer policyVersion;

    @Column(name = "no_policy", nullable = false)
    @Builder.Default
    private boolean noPolicy = false;

    @Column(name = "applied_band_min_days")
    private Integer appliedBandMinDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "applied_deduction_type", length = 10)
    private DeductionType appliedDeductionType;

    @Column(name = "applied_deduction_value", precision = 12, scale = 2)
    private BigDecimal appliedDeductionValue;

    @Column(name = "days_before_departure")
    private Integer daysBeforeDeparture;

    // ── Retained charge (money kept) ──────────────────────────────────────────
    @Column(name = "base_considered", precision = 12, scale = 2)
    private BigDecimal baseConsidered;

    @Column(name = "system_computed_charge_base", precision = 12, scale = 2)
    private BigDecimal systemComputedChargeBase;

    @Column(name = "final_charge_base", precision = 12, scale = 2)
    private BigDecimal finalChargeBase;

    @Column(name = "override_applied", nullable = false)
    @Builder.Default
    private boolean overrideApplied = false;

    @Column(name = "overridden_by_user_id")
    private Long overriddenByUserId;

    @Column(name = "override_reason", columnDefinition = "TEXT")
    private String overrideReason;

    @Column(name = "gst_on_charge", precision = 12, scale = 2)
    private BigDecimal gstOnCharge;

    @Column(name = "tcs_retained", precision = 12, scale = 2)
    private BigDecimal tcsRetained;

    @Column(name = "total_retained", precision = 12, scale = 2)
    private BigDecimal totalRetained;

    // ── Settlement (customer side) ────────────────────────────────────────────
    /** paidAmount at the instant of cancellation (snapshot). */
    @Column(name = "paid_at_cancel", precision = 12, scale = 2)
    private BigDecimal paidAtCancel;

    /** Signed: positive = agency refunds; negative = customer owes. */
    @Column(name = "refund_due", precision = 12, scale = 2)
    private BigDecimal refundDue;

    @Column(name = "customer_balance_owed", precision = 12, scale = 2)
    private BigDecimal customerBalanceOwed;

    @Column(name = "customer_owes", nullable = false)
    @Builder.Default
    private boolean customerOwes = false;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "refund_status", nullable = false, length = 20)
    private RefundStatus refundStatus = RefundStatus.NOT_APPLICABLE;

    // ── Agency P&L (internal — never on a customer document) ──────────────────
    @Column(name = "sunk_vendor_cost", precision = 12, scale = 2)
    private BigDecimal sunkVendorCost;

    @Column(name = "vendor_recoverable", precision = 12, scale = 2)
    private BigDecimal vendorRecoverable;

    @Column(name = "revised_net_profit", precision = 12, scale = 2)
    private BigDecimal revisedNetProfit;

    // ── Documents (populated in the documents phase) ──────────────────────────
    /** CN-YY-NNNN when refundDue >= 0, DBN-YY-NNNN when the customer owes. */
    @Column(name = "credit_note_number", length = 30)
    private String creditNoteNumber;

    @Column(name = "credit_note_doc_public_id")
    private UUID creditNoteDocPublicId;
}