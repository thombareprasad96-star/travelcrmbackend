package com.crm.travelcrm.booking.entity;

import com.crm.travelcrm.booking.enums.ExpenseCostType;
import com.crm.travelcrm.booking.enums.ExpensePaymentStatus;
import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One cost line the agency incurred on a booking — a hotel payment, a flight ticket, a transfer,
 * a guide's fee — together with how much of it has actually been paid out to the vendor. Rows form
 * a per-booking expense ledger with a vendor-payable balance, which is what the Booking → Expense
 * Ledger screen writes.
 *
 * <p><b>Distinct from its two siblings.</b> {@link BookingServiceItem} is the OPERATIONAL
 * breakdown (what the traveller gets, with a vendor assignment and a voucher); this is the
 * FINANCIAL one (what the agency spent, and what it still owes). {@link BookingPayment} is the
 * opposite direction of money — receipts IN from the customer. A booking can carry any mix of the
 * three, and none of them derives from another.
 *
 * <p><b>It never touches {@code Booking.vendorCost}, and only INTERNAL lines reach
 * {@code netProfit}.</b> The original rule here was absolute — expenses mutated no booking total —
 * because rolling them into {@code vendorCost} would double-count against the cost the user already
 * typed there. That reasoning still stands, and {@code vendorCost} is still never written by this
 * ledger. What changed is that profit now also nets off the agency's OWN costs
 * ({@code netProfit = customerAmount − vendorCost − totalInternalCosts}), and the double-count is
 * prevented structurally instead of by abstention: {@link #getCostType()} splits supplier cost from
 * agency overhead, and only {@link com.crm.travelcrm.booking.enums.ExpenseCostType#INTERNAL} rows
 * are summed. A {@link com.crm.travelcrm.booking.enums.ExpenseCostType#VENDOR} row behaves exactly
 * as before — reported by {@code GET /api/bookings/{id}/expenses/summary}, invisible to the
 * booking's totals. Recalculation runs through {@code BookingProfitService}, which is idempotent
 * and no-ops when nothing changed, so it does not fill the Envers trail with machine-written
 * revisions.
 *
 * <p><b>The three money fields are always internally consistent.</b> {@code amount} is what the
 * line cost, {@code paidAmount} is what has been disbursed (0 ≤ paidAmount ≤ amount), and
 * {@code paymentStatus} is DERIVED from the two by {@code ExpenseSettlementCalculator} on every
 * write — never taken from the client. The outstanding balance is not stored at all; it is
 * {@link #getOutstandingAmount()}, so it can never drift from the columns it is computed from.
 *
 * <p>The vendor is a free-text {@code vendorName} rather than a link to the vendor master: the
 * expense form is a fast cash-book entry where the payee is often a driver, a local guide or a
 * counter that was never onboarded as a {@code Vendor}. A line that IS against a real vendor is
 * better recorded as a {@code VendorBill} in the accounting module, which carries TDS and GST.
 *
 * <p>Tenant-scoped ({@code tenant_id} auto-stamped by {@code TenantEntityListener}); soft-delete
 * via {@code deletedAt} on {@code BaseEntity}. Not registered with {@code TrashableType} and not
 * {@code @Audited} — matching both siblings, which are booking sub-resources rather than
 * independently restorable records.
 */
@Entity
@Table(
        name = "booking_expenses",
        indexes = {
                @Index(name = "idx_bkexp_tenant",  columnList = "tenant_id"),
                @Index(name = "idx_bkexp_booking", columnList = "tenant_id,booking_id"),
                @Index(name = "idx_bkexp_deleted", columnList = "tenant_id,deleted_at"),
                // Serves the hot path of the profit recalc: the INTERNAL-only sum for one booking.
                @Index(name = "idx_bkexp_cost_type", columnList = "booking_id,cost_type,deleted_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class BookingExpense extends BaseTenantEntity {

    /** Owning booking — logical FK to {@code bookings.id} (no DB constraint, app-enforced). */
    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    /**
     * e.g. "Hotel", "Flight", "Transport", "Sightseeing", "Visa", "Meals", "Guide", "Commission",
     * "Refund", "Office Expense", "Other" — free text, exactly as the form's list sends it. Kept a
     * String rather than an enum for the same reason {@link BookingPayment#getPaymentMethod()} is:
     * the UI's list can grow without a backend change, a migration and a CHECK-constraint refresh.
     */
    @Column(name = "category", length = 60)
    private String category;

    /**
     * Whether this line is supplier cost or the agency's own overhead — the discriminator that
     * decides if it reaches {@code Booking.netProfit}. See {@link ExpenseCostType}.
     *
     * <p>Unlike {@link #category} this IS an enum, deliberately: category is a display label the UI
     * may extend freely, whereas this drives a money calculation and must have exactly two values a
     * report can rely on. Defaults to {@link ExpenseCostType#VENDOR} so every pre-existing row is
     * classified the safe way and no booking's stored profit shifts on deploy.
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "cost_type", nullable = false, length = 10)
    private ExpenseCostType costType = ExpenseCostType.VENDOR;

    /** What the money was for, e.g. "Hotel payment for 3 nights". */
    @Column(name = "description", nullable = false, length = 300)
    private String description;

    /** Who was paid — free text; see the class javadoc for why this is not a vendor FK. */
    @Column(name = "vendor_name", length = 200)
    private String vendorName;

    /** What the line cost in total. Always > 0. */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** How much of {@link #amount} has been disbursed so far. Invariant: 0 ≤ paidAmount ≤ amount. */
    @Builder.Default
    @Column(name = "paid_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    /** DERIVED from {@link #amount} + {@link #paidAmount} on every write — never client-supplied. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 10)
    private ExpensePaymentStatus paymentStatus = ExpensePaymentStatus.CREDIT;

    /**
     * How the disbursement was tendered ("Cash", "UPI", "Bank Transfer", "Cheque", …). Free text
     * for the same reason as {@link #category}; null while nothing has been paid.
     */
    @Column(name = "payment_mode", length = 40)
    private String paymentMode;

    /** The date the cost was incurred — the ledger's sort key, not the date it was paid. */
    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    /** When the balance falls due to the vendor. Optional; only meaningful while unsettled. */
    @Column(name = "due_date")
    private LocalDate dueDate;

    /** UTR, cheque number, bill or receipt number for the disbursement. */
    @Column(name = "reference_number", length = 120)
    private String referenceNumber;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * The platform hotel-marketplace booking this payable belongs to, when it came from there.
     *
     * <p>Marks the rows the SERVER maintains. A marketplace payable is a VENDOR line the tenant never
     * typed, so {@code Booking.vendorCost} — which this enum's contract says already holds everything
     * paid to suppliers — has to be kept in step with it. {@code sumMarketplacePayable} is how.</p>
     */
    @Column(name = "marketplace_booking_public_id")
    private UUID marketplaceBookingPublicId;

    /**
     * What is still payable to the vendor on this line. Deliberately NOT a column: a stored
     * balance is one more thing that can drift from the two figures it is derived from, and the
     * client sends its own {@code outstandingAmount} which is ignored on the way in for exactly
     * that reason. Floored at zero so a legacy row with paidAmount > amount can never report a
     * negative payable.
     */
    @Transient
    public BigDecimal getOutstandingAmount() {
        if (amount == null) return BigDecimal.ZERO;
        BigDecimal paid = paidAmount != null ? paidAmount : BigDecimal.ZERO;
        BigDecimal outstanding = amount.subtract(paid);
        return outstanding.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : outstanding;
    }

    /** True while money is still owed on this line — what a payables view filters on. */
    @Transient
    public boolean isUnsettled() {
        return getOutstandingAmount().compareTo(BigDecimal.ZERO) > 0;
    }
}
