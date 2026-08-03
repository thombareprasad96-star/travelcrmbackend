package com.crm.travelcrm.fleet.money;

import com.crm.travelcrm.fleet.enums.FleetCashDirection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The driver's <em>hisaab</em>: given his cash movements, what he spent from that cash, and what he
 * was entitled to, how much does he still owe the company — or it him.
 *
 * <p>Pure: no Spring, no database, no clock. Every rule here is a rupee somebody hands over at the
 * end of a trip, so all of it is unit-tested.
 *
 * <h2>The identity</h2>
 * <pre>
 *   netDueFromDriver = Σ(direction.signum × baseAmount) − driverCashSpend − allowanceTotal
 * </pre>
 * Positive means he is still holding company money. Negative means the company owes him. Zero means
 * squared, and only then may the settlement be signed.
 *
 * <h2>The two traps this class exists to avoid</h2>
 * <ol>
 *   <li><b>Double-subtracting the allowance.</b> Bata and night halt are real trip costs AND money
 *       the driver keeps — but he keeps them out of the advance he is already holding. So they are
 *       discharged exactly once, through {@code allowanceTotal}. If a bata expense row marked
 *       DRIVER_CASH also lands in {@code driverCashSpend}, the same 1,200 is subtracted twice: on an
 *       8,000 advance with 5,000 of real spend the driver is asked to return 600 instead of 1,800,
 *       and walks away with 1,200 he was never owed. At twenty vehicles and twenty trips a month
 *       that is roughly Rs 4.8 lakh a year, and every settlement reports "balanced" while it happens.
 *       The caller MUST exclude system-computed types — see {@code FleetExpense.consumesDriverCash()}
 *       and the matching exclusion in {@code FleetExpenseRepository.sumDriverCashSpend}.</li>
 *   <li><b>Signed amounts.</b> Amounts are always positive; the direction carries the sign. Allowing
 *       a negative amount mints money — a {@code CASH_RETURN} of −1,800 would ADD 1,800 to what the
 *       driver owes instead of discharging it, a Rs 3,600 swing from one keystroke. Rejected here as
 *       well as by a CHECK constraint, because this calculator is also fed by imports and tests.</li>
 * </ol>
 */
public final class FleetSettlementCalculator {

    private FleetSettlementCalculator() {
    }

    /** One cash movement, reduced to what the arithmetic needs. */
    public record CashMovement(FleetCashDirection direction, BigDecimal baseAmount) {
    }

    /**
     * @param advanceTotal      Σ ADVANCE_OUT
     * @param collectedTotal    Σ CUSTOMER_COLLECTION — company money picked up on the road
     * @param returnedTotal     Σ CASH_RETURN — unspent float coming back
     * @param depositedTotal    Σ COLLECTION_DEPOSIT — customer money banked, kept separate so the
     *                          deposit reconciles and the booking is not receipted twice
     * @param adjustmentTotal   Σ RECOVERY + ADJUSTMENT_DEBIT − ADJUSTMENT_CREDIT
     * @param driverCashSpend   what he spent from the advance, EXCLUDING system-computed types
     * @param allowanceTotal    bata + night halt, from policy
     * @param netDueFromDriver  &gt;0 he owes; &lt;0 the company owes him; 0 squared
     */
    public record Settlement(
            BigDecimal advanceTotal,
            BigDecimal collectedTotal,
            BigDecimal returnedTotal,
            BigDecimal depositedTotal,
            BigDecimal adjustmentTotal,
            BigDecimal driverCashSpend,
            BigDecimal allowanceTotal,
            BigDecimal netDueFromDriver
    ) {
        public boolean isSquared() {
            return netDueFromDriver.compareTo(BigDecimal.ZERO) == 0;
        }
    }

    /**
     * @param movements       the driver's cash entries for this trip. Amounts must be positive.
     * @param driverCashSpend already filtered to exclude bata / night halt
     * @param allowanceTotal  computed from the allowance policy, never typed by the driver
     */
    public static Settlement settle(List<CashMovement> movements,
                                    BigDecimal driverCashSpend,
                                    BigDecimal allowanceTotal) {

        BigDecimal spend = nonNegative(driverCashSpend, "Driver cash spend");
        BigDecimal allowance = nonNegative(allowanceTotal, "Allowance");

        BigDecimal advance = BigDecimal.ZERO;
        BigDecimal collected = BigDecimal.ZERO;
        BigDecimal returned = BigDecimal.ZERO;
        BigDecimal deposited = BigDecimal.ZERO;
        BigDecimal adjustment = BigDecimal.ZERO;
        BigDecimal signedCash = BigDecimal.ZERO;

        for (CashMovement m : movements == null ? List.<CashMovement>of() : movements) {
            if (m == null || m.direction() == null) continue;
            BigDecimal amount = positive(m.baseAmount(), m.direction());

            // One signed accumulator drives the answer; the rest are presentation totals for the
            // printed sheet. Deriving the net from the buckets instead would mean re-deciding each
            // sign in a second place, which is exactly how a settlement quietly pays twice.
            signedCash = signedCash.add(amount.multiply(BigDecimal.valueOf(m.direction().signum())));

            switch (m.direction()) {
                case ADVANCE_OUT -> advance = advance.add(amount);
                case CUSTOMER_COLLECTION -> collected = collected.add(amount);
                case CASH_RETURN -> returned = returned.add(amount);
                case COLLECTION_DEPOSIT -> deposited = deposited.add(amount);
                case RECOVERY, ADJUSTMENT_DEBIT -> adjustment = adjustment.add(amount);
                case ADJUSTMENT_CREDIT -> adjustment = adjustment.subtract(amount);
            }
        }

        BigDecimal net = signedCash.subtract(spend).subtract(allowance)
                .setScale(FleetMoneyCalculator.MONEY_SCALE, RoundingMode.HALF_UP);

        return new Settlement(scale(advance), scale(collected), scale(returned), scale(deposited),
                scale(adjustment), scale(spend), scale(allowance), net);
    }

    private static BigDecimal positive(BigDecimal amount, FleetCashDirection direction) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Cash amount must be positive — " + direction
                            + " carries its own sign, so a negative amount would reverse its meaning");
        }
        return amount;
    }

    private static BigDecimal nonNegative(BigDecimal value, String label) {
        if (value == null) return BigDecimal.ZERO;
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(label + " cannot be negative");
        }
        return value;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(FleetMoneyCalculator.MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
