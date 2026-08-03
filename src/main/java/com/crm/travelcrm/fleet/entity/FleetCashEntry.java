package com.crm.travelcrm.fleet.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.fleet.enums.FleetCashDirection;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A movement on a driver's cash (imprest) account — the loop every practitioner named first:
 * <em>peshgi</em> out, spends against it, cash back, signed <em>hisaab</em>.
 *
 * <p><b>This is the table the previous plan had no concept of at all.</b> It framed driver money as
 * "reimbursement", which inverts the reality: the office hands over Rs 8,000 before departure, so at
 * the end the driver is being SETTLED, not reimbursed. Without a running imprest balance there is no
 * way to answer the question an owner asks every night — how much of my cash is out on the road, and
 * with whom.
 *
 * <p><b>Amounts are always positive.</b> Direction carries the sign, via
 * {@link FleetCashDirection#signum()}. Allowing a signed amount means one stray minus mints money:
 * a {@code CASH_RETURN} of -1,800 would ADD 1,800 to what the driver owes instead of discharging it,
 * a Rs 3,600 swing from a single keystroke with nothing between the phone and the balance. Write-offs
 * use {@code ADJUSTMENT_CREDIT}, not a negative number.
 *
 * <p><b>Customer money is tracked separately from company float.</b> {@code CUSTOMER_COLLECTION} and
 * {@code COLLECTION_DEPOSIT} are economically different rupees from an advance and its return: they
 * belong to a customer and must be receipted against a booking. Folding them into one direction
 * produces a settlement sheet showing a driver returning more than he was ever given, and a bank
 * deposit nobody can split.
 */
@Entity
@Table(name = "fleet_cash_entries", indexes = {
        @Index(name = "idx_fleet_cash_tenant_date", columnList = "tenant_id,entry_date"),
        // The driver-balance query: every read of "what does he owe" scans this.
        @Index(name = "idx_fleet_cash_driver", columnList = "tenant_id,driver_id,entry_date"),
        @Index(name = "idx_fleet_cash_trip", columnList = "tenant_id,trip_id"),
        @Index(name = "idx_fleet_cash_posting", columnList = "tenant_id,posting_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class FleetCashEntry extends BaseTenantEntity {

    /** Always required — a cash movement with no driver has no account to move. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_fleet_cash_driver"))
    private FleetDriver driver;

    /**
     * Optional: an advance is usually against a trip, but an opening balance, a general float or a
     * recovery for an off-duty challan is not.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", foreignKey = @ForeignKey(name = "fk_fleet_cash_trip"))
    private FleetTrip trip;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 24)
    private FleetCashDirection direction;

    /** Always positive. The sign comes from {@link #direction}. */
    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    @Column(name = "fx_rate", nullable = false, precision = 18, scale = 8)
    @Builder.Default
    private BigDecimal fxRate = BigDecimal.ONE;

    @Column(name = "base_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal baseAmount;

    /** When the cash physically moved. */
    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    /** Accounting period. Same two-date rule as {@code FleetExpense}: a late correction posts today. */
    @Column(name = "posting_date", nullable = false)
    private LocalDate postingDate;

    /**
     * Mandatory for ADJUSTMENT_DEBIT, ADJUSTMENT_CREDIT and RECOVERY — see
     * {@link FleetCashDirection#requiresReason()}. Charging a driver for a challan or writing off a
     * shortfall without a recorded reason is exactly the kind of entry that turns into a dispute.
     */
    @Column(name = "reason", length = 300)
    private String reason;

    /** UTR, cheque number, or the booking code the collection belongs to. */
    @Column(name = "reference_number", length = 60)
    private String referenceNumber;

    /**
     * Party the customer money belongs to, for {@code CUSTOMER_COLLECTION} / {@code COLLECTION_DEPOSIT}.
     * Free text in a standalone deployment; the booking code in CRM mode. Without it, a Rs 12,000
     * collection cannot be matched to the booking it should be receipted against, and the CRM can
     * receipt the same rupees a second time.
     */
    @Column(name = "party_reference", length = 120)
    private String partyReference;

    /**
     * Correction pointer, exactly as on {@code FleetExpense}. The plan gave reversal semantics to
     * expenses only — so an advance typed as 80,000 instead of 8,000 and then settled had no correction
     * path at all, and ADJUSTMENT could only ever add more in the wrong direction.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reversal_of_id", foreignKey = @ForeignKey(name = "fk_fleet_cash_reversal"))
    private FleetCashEntry reversalOf;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Version
    @Column(name = "row_version")
    private Long rowVersion;

    /** Signed effect on what the driver owes the company, in base currency. */
    public BigDecimal signedBaseAmount() {
        return baseAmount == null ? BigDecimal.ZERO
                : baseAmount.multiply(BigDecimal.valueOf(direction.signum()));
    }
}
