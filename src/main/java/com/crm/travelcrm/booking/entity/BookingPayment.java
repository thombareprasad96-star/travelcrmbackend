package com.crm.travelcrm.booking.entity;

import com.crm.travelcrm.booking.enums.PaymentEntryType;
import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A single payment received against a booking — one row per receipt, forming an itemised
 * ledger. The parent {@link Booking#getPaidAmount()} is kept in step by the service on every
 * add/delete (it is treated as the running balance; a ledger row is always a positive amount
 * that increases it). An optional {@code serviceItemId} attributes the receipt to one
 * {@link BookingServiceItem} for per-service payment history; when null it is a booking-level
 * payment.
 *
 * <p>Tenant-scoped ({@code tenant_id} auto-stamped by {@code TenantEntityListener}); soft-delete
 * via {@code deletedAt} on {@code BaseEntity}.</p>
 */
@Entity
@Table(
        name = "booking_payments",
        indexes = {
                @Index(name = "idx_bkpay_tenant",  columnList = "tenant_id"),
                @Index(name = "idx_bkpay_booking", columnList = "tenant_id,booking_id"),
                @Index(name = "idx_bkpay_service", columnList = "service_item_id"),
                @Index(name = "idx_bkpay_deleted", columnList = "tenant_id,deleted_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class BookingPayment extends BaseTenantEntity {

    /** Owning booking — logical FK to {@code bookings.id} (no DB constraint, app-enforced). */
    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    /** Optional attribution to one service line — logical FK to {@code booking_service_items.id}. */
    @Column(name = "service_item_id")
    private Long serviceItemId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /**
     * Ledger direction. RECEIPT (money in → paidAmount) or REFUND (money out → refundedAmount).
     * Left NULLABLE at the DB so the column can be added to an existing table; legacy NULL rows are
     * read as RECEIPT (see {@link #isRefund()}). New rows always carry a value (builder default).
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", length = 10)
    private PaymentEntryType entryType = PaymentEntryType.RECEIPT;

    /**
     * Optional client-supplied idempotency token for a refund disbursement — a UNIQUE partial index
     * ({@code uq_bkpay_idem}, see {@code db/indexes.sql}) makes a re-submitted payout collapse to the
     * original row instead of double-paying. Null for ordinary receipts.
     */
    @Column(name = "idempotency_key", length = 80)
    private String idempotencyKey;

    /** Free display label (e.g. "Advance", "Partial", "Balance", "Refund") — informational, not logic. */
    @Column(name = "payment_type", length = 40)
    private String paymentType;

    /**
     * How the payment was tendered — a free label as entered on the form
     * ("Cash", "Bank Transfer", "UPI", "Credit Card", "Wallet", …). Kept as free text rather than
     * a constrained enum so the UI's method list can grow without a backend change.
     */
    @Column(name = "payment_method", length = 40)
    private String paymentMethod;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Column(name = "reference", length = 120)
    private String reference;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** A refund (money out). Legacy NULL rows predate the column and are ordinary receipts. */
    @Transient
    public boolean isRefund() {
        return entryType == PaymentEntryType.REFUND;
    }
}