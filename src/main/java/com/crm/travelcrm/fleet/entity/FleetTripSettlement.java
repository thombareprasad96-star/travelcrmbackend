package com.crm.travelcrm.fleet.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.fleet.enums.FleetSettlementStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One driver's <em>hisaab</em> for one trip: advance out, what he spent, what he collected, what he
 * returned, what he was owed — reconciled, signed, frozen.
 *
 * <p><b>Keyed on (trip, driver), NOT on trip alone.</b> This is the correction that matters most.
 * A trip is multi-driver by design once legs exist, and a single scalar settlement row nets two
 * drivers into a number that is right for neither. Concretely, on a six-day trip handed over on day
 * three — advances A 8,000 and B 5,000, driver-cash spend A 3,000 and B 6,000, bata 1,200 each — a
 * trip-level row computes 13,000 − 9,000 − 2,400 = +1,600 and can be marked reconciled and signed
 * once. The truth is that A owes 3,800 and B is owed 2,200; neither figure is ever collected or
 * paid, and A's signature releases B.
 *
 * <p><b>Totals are stored but always re-derived inside the settle lock.</b> Storing them is what
 * makes the sheet printable and auditable after the fact; re-deriving them at settle time is what
 * stops the race the row version cannot see. Two settle calls contend on this row and are
 * serialised — but a settle racing a late expense INSERT touches two different rows, so optimistic
 * locking never engages. That is a real Rs 900-goes-missing scenario, so every expense and cash
 * write for a trip first takes this row {@code FOR UPDATE}, which also serialises the
 * {@link FleetSettlementStatus#isMutable()} check that would otherwise be an unguarded
 * read-then-write.
 *
 * @see FleetSettlementStatus for why there is no per-expense approval ladder
 */
@Entity
@Table(name = "fleet_trip_settlements", indexes = {
        @Index(name = "idx_fleet_settle_tenant", columnList = "tenant_id"),
        @Index(name = "idx_fleet_settle_trip", columnList = "tenant_id,trip_id"),
        // The "who still owes me money" worklist — the owner's 10pm screen.
        @Index(name = "idx_fleet_settle_driver_status", columnList = "tenant_id,driver_id,status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class FleetTripSettlement extends BaseTenantEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_fleet_settle_trip"))
    private FleetTrip trip;

    /** One settlement per driver per trip — unique together (index in SQL). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_fleet_settle_driver"))
    private FleetDriver driver;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    @Builder.Default
    private FleetSettlementStatus status = FleetSettlementStatus.OPEN;

    // ── Derived totals, all in base currency. Recomputed under the row lock. ──

    /** Σ ADVANCE_OUT. */
    @Column(name = "advance_total", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal advanceTotal = BigDecimal.ZERO;

    /** Σ CUSTOMER_COLLECTION — company money the driver picked up on the road. */
    @Column(name = "collected_total", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal collectedTotal = BigDecimal.ZERO;

    /** Σ CASH_RETURN. Unspent float coming back — distinct from a collection deposit. */
    @Column(name = "returned_total", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal returnedTotal = BigDecimal.ZERO;

    /** Σ COLLECTION_DEPOSIT — customer money banked, kept apart so the deposit reconciles. */
    @Column(name = "deposited_total", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal depositedTotal = BigDecimal.ZERO;

    /** Σ RECOVERY + ADJUSTMENT_DEBIT − ADJUSTMENT_CREDIT. */
    @Column(name = "adjustment_total", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal adjustmentTotal = BigDecimal.ZERO;

    /**
     * Σ expenses where {@code paidBy = DRIVER_CASH} AND the type is not system-computed. The
     * exclusion is load-bearing — see {@code FleetExpense.consumesDriverCash()}.
     */
    @Column(name = "driver_cash_spend", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal driverCashSpend = BigDecimal.ZERO;

    /** Bata + night halt, computed from the allowance policy and duty days. Never typed by the driver. */
    @Column(name = "allowance_total", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal allowanceTotal = BigDecimal.ZERO;

    /**
     * Positive ⇒ the driver still holds this much of the company's money. Negative ⇒ the company owes
     * him. Zero ⇒ squared, and only then may this reach SETTLED.
     */
    @Column(name = "net_due_from_driver", nullable = false, precision = 14, scale = 2)
    @Builder.Default
    private BigDecimal netDueFromDriver = BigDecimal.ZERO;

    // ── Signature ───────────────────────────────────────────────────────────

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Column(name = "settled_by", length = 150)
    private String settledBy;

    /** When the driver acknowledged the sheet. A settlement is not signed until this is set. */
    @Column(name = "driver_acknowledged_at")
    private LocalDateTime driverAcknowledgedAt;

    /**
     * Raised when an expense or cash entry lands against this settlement AFTER it was signed — a late
     * pump bill, a reversed duplicate. The frozen totals cannot move, so without this flag the
     * driver's live imprest balance and his signed sheet disagree silently and nothing puts the trip
     * back on anyone's worklist.
     */
    @Column(name = "has_post_settlement_movement", nullable = false)
    @Builder.Default
    private boolean hasPostSettlementMovement = false;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Version
    @Column(name = "row_version")
    private Long rowVersion;

    /** Squared when nothing is owed in either direction. The gate for SETTLED. */
    public boolean isSquared() {
        return netDueFromDriver != null && netDueFromDriver.compareTo(BigDecimal.ZERO) == 0;
    }
}
