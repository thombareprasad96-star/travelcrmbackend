package com.crm.travelcrm.fleet.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.fleet.enums.FleetExpenseType;
import com.crm.travelcrm.fleet.enums.FleetPaidBy;
import com.crm.travelcrm.fleet.enums.FleetTaxCharacter;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One money event against a vehicle — a toll, a tank of diesel, a Bhansar receipt, a challan.
 *
 * <p><b>There is no approval workflow on this row.</b> Approval happens once, on the trip's
 * settlement. Four practitioners independently rejected a per-expense DRAFT→SUBMITTED→APPROVED
 * ladder in the same words: nobody performs four approvals for a Rs 640 parking charge, so it gets
 * worked around — costs go unrecorded, or a batch is approved without being read, which is worse
 * than no approval at all.
 *
 * <h2>Two dates, and they are not interchangeable</h2>
 * <ul>
 *   <li>{@link #documentDate} — what the receipt says. Attributes the cost to a trip, a vehicle and
 *       a driver. <b>Never overwritten.</b> The date on the paper is what an assessing officer reads.</li>
 *   <li>{@link #postingDate} — which accounting period the money lands in.</li>
 * </ul>
 * They differ exactly when they must: a Rs 50,000 error found in June against a March receipt is
 * reversed with the ORIGINAL document date (so March's vehicle cost/km is corrected) and TODAY's
 * posting date (so a closed, filed period does not silently move). Collapsing them into one column
 * makes that case unrepresentable, and it is the case that matters most.
 *
 * <h2>Corrections</h2>
 * While the trip's settlement is open, a row is edited in place — a typo at the pump is a typo, not
 * a financial event. Afterwards a correction is a NEW row with {@link #reversalOf} set, dated as
 * above. There is no "void" status flip anywhere: a status flip on an approved row rewrites history,
 * which is exactly what a period lock exists to prevent.
 *
 * <p><b>The canonical aggregate is a naive {@code SUM(base_amount)} over all non-deleted rows.</b>
 * Reversals net against their originals by arithmetic. Do NOT write the "exclude reversed originals"
 * form — over a chain (expense, its reversal, a reversal of that reversal) the two disagree, and the
 * design names this one as correct.
 */
@Entity
@Table(name = "fleet_expenses", indexes = {
        @Index(name = "idx_fleet_exp_tenant_date", columnList = "tenant_id,document_date"),
        @Index(name = "idx_fleet_exp_vehicle_date", columnList = "tenant_id,vehicle_id,document_date"),
        @Index(name = "idx_fleet_exp_trip", columnList = "tenant_id,trip_id"),
        @Index(name = "idx_fleet_exp_driver", columnList = "tenant_id,driver_id"),
        @Index(name = "idx_fleet_exp_type", columnList = "tenant_id,expense_type"),
        @Index(name = "idx_fleet_exp_posting", columnList = "tenant_id,posting_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class FleetExpense extends BaseTenantEntity {

    // ── What it was spent on ────────────────────────────────────────────────

    /** Always required. Every rupee of fleet cost belongs to a physical asset. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_fleet_exp_vehicle"))
    private FleetVehicle vehicle;

    /**
     * Optional — insurance, road tax and an off-duty challan belong to a vehicle, not a trip.
     * Those rows have no settlement, so their immutability comes from the period close instead.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", foreignKey = @ForeignKey(name = "fk_fleet_exp_trip"))
    private FleetTrip trip;

    /**
     * Resolved SERVER-SIDE from the leg covering {@link #documentDatetime} — never defaulted from
     * {@code trip.getVehicle()}. Defaulting from the trip's current-leg pointer is what would post
     * every late-arriving toll on a substituted trip to the wrong vehicle and charge the wrong driver.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leg_id", foreignKey = @ForeignKey(name = "fk_fleet_exp_leg"))
    private FleetTripLeg leg;

    /** Required whenever {@link #paidBy} is DRIVER_CASH — otherwise the spend has no cash account to move. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", foreignKey = @ForeignKey(name = "fk_fleet_exp_driver"))
    private FleetDriver driver;

    @Enumerated(EnumType.STRING)
    @Column(name = "expense_type", nullable = false, length = 24)
    private FleetExpenseType expenseType;

    // ── When ────────────────────────────────────────────────────────────────

    /** The date ON THE RECEIPT. Attribution key. Never overwritten, never derived from entry time. */
    @Column(name = "document_date", nullable = false)
    private LocalDate documentDate;

    /**
     * Time on the receipt, where it exists. Required for TOLL, PARKING and CHALLAN because those are
     * the rows that must resolve to a leg on a multi-driver trip — a date alone is ambiguous when a
     * handover happened that day, and the ambiguity silently charges the wrong driver.
     */
    @Column(name = "document_time")
    private java.time.LocalTime documentTime;

    /** Which accounting period this hits. Defaults to {@link #documentDate}; differs only for a late correction. */
    @Column(name = "posting_date", nullable = false)
    private LocalDate postingDate;

    @Column(name = "entered_at", nullable = false)
    private LocalDateTime enteredAt;

    @Column(name = "entered_by", length = 150)
    private String enteredBy;

    // ── Money ───────────────────────────────────────────────────────────────

    /** As paid, in {@link #currency}. Always positive — a negative is expressed by a reversal row. */
    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    /**
     * Rate to base currency, copied from the trip at write time — never sent by a client. A driver's
     * phone cannot set an exchange rate; the office sets one rate for the Nepal trip and every row
     * inherits it. {@code numeric(18,8)}: at {@code numeric(14,2)} a rate of 0.625 would store as
     * 0.63 and turn NPR 100,000 of Bhansar into Rs 63,000 instead of Rs 62,500.
     */
    @Column(name = "fx_rate", nullable = false, precision = 18, scale = 8)
    @Builder.Default
    private BigDecimal fxRate = BigDecimal.ONE;

    /** {@code amount * fxRate}, HALF_UP to 2dp, computed server-side. The only figure reports may sum. */
    @Column(name = "base_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal baseAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "paid_by", nullable = false, length = 16)
    private FleetPaidBy paidBy;

    // ── Evidence ────────────────────────────────────────────────────────────

    /**
     * "No receipt" is a first-class, honest answer — not a validation failure. Half the tolls and
     * most small parking charges never produce one, and a system that demands a photo above a
     * threshold simply gets fake photos or unrecorded spend.
     */
    @Column(name = "has_receipt", nullable = false)
    @Builder.Default
    private boolean hasReceipt = false;

    /** Required when {@link #hasReceipt} is false. Free text, because the real reasons are varied. */
    @Column(name = "no_receipt_reason", length = 200)
    private String noReceiptReason;

    @Column(name = "reference_number", length = 60)
    private String referenceNumber;

    // ── Tax identity: what makes fleet money visible to the tenant's own books ───

    @Column(name = "supplier_gstin", length = 15)
    private String supplierGstin;

    @Column(name = "taxable_value", precision = 14, scale = 2)
    private BigDecimal taxableValue;

    @Column(name = "gst_rate", precision = 5, scale = 2)
    private BigDecimal gstRate;

    @Column(name = "gst_amount", precision = 14, scale = 2)
    private BigDecimal gstAmount;

    /**
     * Whether the GST on this row is claimable. NOT derivable from the expense type: fuel carries GST
     * but the credit is blocked, tolls are exempt outright, while parking, repairs, tyres and
     * insurance are claimable. The accountant owns this call.
     */
    @Column(name = "itc_eligible", nullable = false)
    @Builder.Default
    private boolean itcEligible = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_character", nullable = false, length = 20)
    @Builder.Default
    private FleetTaxCharacter taxCharacter = FleetTaxCharacter.ALLOWABLE;

    // ── Corrections ─────────────────────────────────────────────────────────

    /**
     * Set on a reversal row, pointing at the row it cancels. Unique among non-null values (partial
     * index in SQL) so a double-clicked Reverse cannot insert two -5,000 rows and drive a vehicle's
     * monthly fuel to a negative number. One level only: a reversal of a reversal is rejected.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reversal_of_id", foreignKey = @ForeignKey(name = "fk_fleet_exp_reversal"))
    private FleetExpense reversalOf;

    @Column(name = "reversal_reason", length = 300)
    private String reversalReason;

    @Column(name = "description", length = 300)
    private String description;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * Guards concurrent edits of the same row. Note this does NOT guard settle-racing-an-insert —
     * those touch different rows entirely — which is why the settlement row is taken as a lock before
     * any expense write. See {@code FleetSettlementService}.
     */
    @Version
    @Column(name = "row_version")
    private Long rowVersion;

    /** True when this row is a correction of another. */
    public boolean isReversal() {
        return reversalOf != null;
    }

    /**
     * Does this row consume the driver's cash advance? False for bata/night-halt even when marked
     * DRIVER_CASH: the driver keeps those out of the advance he is already holding, and the
     * settlement discharges them once via his entitlement. Counting them here as well
     * double-subtracts — on an 8,000 advance with 5,000 real spend and 1,200 bata the driver would
     * be asked to return 600 instead of 1,800.
     */
    public boolean consumesDriverCash() {
        return paidBy == FleetPaidBy.DRIVER_CASH && !expenseType.isSystemComputed();
    }
}
