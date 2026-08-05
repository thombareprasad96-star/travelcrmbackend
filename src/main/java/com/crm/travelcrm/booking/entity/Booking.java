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
import java.math.RoundingMode;
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
                @Index(name = "idx_booking_deleted",     columnList = "tenant_id,deleted_at"),
                @Index(name = "idx_booking_assigned_user", columnList = "tenant_id,assigned_user_id")
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

    /**
     * The same customer as {@code customerId}, by publicId — denormalised on purpose, exactly as
     * {@link #sourceLeadPublicId} is.
     *
     * <p>The API must return the UUID (the internal Long is never exposed and no endpoint accepts
     * it), and resolving it per row at response time would be an N+1 across every booking list,
     * export and report in the system. Stamped wherever {@code customerId} is stamped; the two are
     * always written together.
     */
    @Column(name = "customer_public_id")
    private java.util.UUID customerPublicId;

    // No DB-level FK — cross-aggregate reference to leads.id, enforced at the application layer.
    @Column(name = "lead_id")
    private Long leadId;

    // The staff member RESPONSIBLE for servicing this booking — who the customer's calls come to.
    // Distinct from both neighbours it sits between:
    //   ownerUserId → row-level data scope (who may SEE the row); stamped by OwnershipEntityListener.
    //   createdBy   → audit (which email pressed the button).
    // On conversion this defaults to the LEAD's assignee — the person who nurtured the deal keeps it
    // — not whoever clicked Convert. Editable at conversion/creation time.
    //
    // A plain Long logical FK, matching customerId / leadId / ownerUserId on this entity: Booking
    // deliberately holds no DB-level FKs to other aggregates and validates them in the service.
    // (Lead uses a real @ManyToOne for its assignee; Booking's convention is the opposite one.)
    // Nullable — every booking created before this column existed has none.
    @Column(name = "assigned_user_id")
    private Long assignedUserId;

    // ── Trip detail (who is travelling, from where, the route) ────────────────
    /**
     * Point-in-time snapshot of the traveller/departure/itinerary detail this booking was sold with.
     * Optional — a booking taken over the phone may carry only money and a destination.
     *
     * <p>{@code @NotAudited} on the FIELD as well as on the target entity: Envers refuses an audited
     * entity holding an audited-by-default association to a non-audited one, and revisioning an
     * immutable snapshot on every booking change would store copies of a thing that never changes.
     *
     * <p>{@code orphanRemoval} + {@code CascadeType.ALL} so the snapshot lives and dies with its
     * booking; nothing else references it.
     */
    @org.hibernate.envers.NotAudited
    @OneToOne(mappedBy = "booking", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    private BookingTripSnapshot tripSnapshot;

    /** Keeps the two sides of the association consistent — set one, get both. */
    public void attachTripSnapshot(BookingTripSnapshot snapshot) {
        if (snapshot != null) snapshot.setBooking(this);
        this.tripSnapshot = snapshot;
    }

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

    // Whether this booking is an overseas tour programme package. Drives TCS when the tenant's
    // policy is OVERSEAS_ONLY — the setting that matches the statute for an agency selling both
    // domestic and outbound, since TCS under s.206C(1G)/394 does not reach domestic packages.
    //
    // Per CBDT Circular 10/2023 a package qualifies only if it bundles at least TWO of
    // {international ticket, hotel accommodation, other similar expenditure} — a bare international
    // air ticket is not one. That judgement is the agent's, so this is an explicit flag rather than
    // something inferred from the destination.
    //
    // Defaults false: a booking nobody classified is treated as domestic, which under-collects
    // rather than over-collects, and over-collecting someone else's tax is the worse error.
    @Builder.Default
    @Column(name = "overseas_tour_package", nullable = false)
    private boolean overseasTourPackage = false;

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

    // Sum of the ACTIVE, INTERNAL-typed rows of this booking's expense ledger — the agency's own
    // cost of doing the booking (staff commission, marketing, gateway fees, courier), over and above
    // what it paid suppliers. VENDOR-typed expense rows are deliberately excluded: they would
    // double-count against the vendorCost typed above. See ExpenseCostType.
    //
    // Denormalised on purpose. It is the third term of netProfit, and storing it means the stored
    // margin is explainable without re-reading the ledger, every report reads one row instead of
    // joining, and a historic profit figure can still be reconciled after rows are edited. The
    // ledger remains the source of truth; BookingProfitService is the only writer and recomputes
    // both fields together, so the two can never disagree.
    @Builder.Default
    @Column(name = "total_internal_costs", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalInternalCosts = BigDecimal.ZERO;

    // customerAmount − vendorCost − totalInternalCosts. Stored, never computed at read time, so
    // dashboards and reports all read one agreed figure instead of each re-deriving it.
    // BookingProfitService owns every write.
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

    /** Money is quoted to the paise everywhere; a bare {@code BigDecimal.ZERO} would read as "0". */
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2);

    /**
     * The LIVE receivable on this booking — what the customer still has to pay.
     *
     * <p><b>Zero once the booking is CANCELLED or REFUNDED, and that is the point.</b>
     * {@code totalPayable} and {@code paidAmount} are both frozen at their pre-cancellation values
     * (deliberately — they are the historical record of what was sold and what was received), so
     * {@code totalPayable − paidAmount} on a cancelled booking is the unpaid balance of a trip that
     * will never happen. Nobody owes it. Every reader of this getter — the response DTOs, the CSV
     * export, the invoice/voucher model, the traveler portal, the calendar, the AI booking tool —
     * would otherwise each have to know to special-case cancellation, and they did not agree:
     * {@code PortalPaymentService} guarded it explicitly while the CSV export and the AI tool
     * printed the fiction.
     *
     * <p>What a cancelled booking actually settles at is a different figure entirely, and it lives
     * on the immutable {@code BookingCancellation} record ({@code totalRetained} / {@code refundDue}
     * / {@code customerBalanceOwed}), served by the cancellation summary endpoint. It is
     * document-backed (credit/debit note) and frozen; this denormalised getter has no access to it
     * and must not guess at it.
     *
     * <p>Floored at zero and scaled to the paise, matching {@code previewFinancials} — an invoice
     * must never print a negative "balance due".
     */
    @Transient
    public BigDecimal getPendingAmount() {
        if (status == BookingStatus.CANCELLED || status == BookingStatus.REFUNDED) return ZERO_MONEY;
        if (totalPayable == null || paidAmount == null) return ZERO_MONEY;
        return totalPayable.subtract(paidAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }
}