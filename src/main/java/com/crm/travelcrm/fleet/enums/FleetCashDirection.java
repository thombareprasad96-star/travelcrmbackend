package com.crm.travelcrm.fleet.enums;

/**
 * A movement on a driver's cash (imprest) account — the loop every practitioner named first:
 * <em>peshgi</em> out, spends against it, cash back, signed <em>hisaab</em>.
 *
 * <p><b>Sign convention: {@link #signum()} is the effect on what the driver OWES the company</b> —
 * {@code +1} increases his obligation, {@code -1} discharges it. Getting this backwards is the
 * easiest way to build a settlement that silently pays a driver twice, so it is stated once here and
 * every accumulator reads it from this enum rather than deciding for itself.
 *
 * <p><b>Why {@link #CUSTOMER_COLLECTION} lives here and not on the booking.</b> When a driver takes
 * the balance payment from the party on the road, that cash is physically in his pocket. Recording
 * it as a note on an invoice — which is what happens today — loses it: the office thinks the driver
 * owes Rs 2,000 when he is actually holding Rs 12,000 of theirs. It is a movement on his account
 * first; the booking receipt is a separate, later act.
 *
 * <p><b>Why {@link #RECOVERY} is not a negative expense.</b> Recovering a challan from a driver does
 * not reduce what the fine cost the company — the fine was still paid to the authority. Netting the
 * two makes an expensive vehicle look free. The fine is an expense; the recovery is a cash movement.
 */
public enum FleetCashDirection {

    /** Office hands the driver cash before/during a trip. He now owes it back or must account for it. */
    ADVANCE_OUT("Advance given", +1),

    /** Driver returns unspent cash at settlement. */
    CASH_RETURN("Cash returned", -1),

    /** Driver collected money from the customer on the road — company cash, in his pocket. */
    CUSTOMER_COLLECTION("Collected from customer", +1),

    /**
     * Charged to the driver by an explicit recorded decision — a challan he is held responsible for,
     * damage, an unexplained shortfall. {@code +1}: it INCREASES what he owes, it does not reduce
     * the company's cost. The fine was still paid to the authority; netting the two would make an
     * expensive vehicle look free.
     */
    RECOVERY("Charged to driver", +1),

    /**
     * Driver banks/hands over money he collected FROM A CUSTOMER, as distinct from returning unspent
     * advance. Economically different rupees: this is customer money awaiting a booking receipt,
     * {@link #CASH_RETURN} is the company's own float coming back. Folding both into one direction
     * produces a settlement sheet showing a driver returning more than he was ever given, and a bank
     * deposit nobody can split.
     */
    COLLECTION_DEPOSIT("Deposited collections", -1),

    /**
     * Manual correction, mandatory typed reason. Split by SIGN so the direction is a domain decision
     * rather than a data-entry artefact — a single ADJUSTMENT constant forces the sign into the
     * amount, and once amounts may be negative one stray minus mints money on every other direction
     * (a {@code CASH_RETURN} of -1800 would ADD 1800 to what the driver owes).
     *
     * <p>Amounts are always positive. Every direction here means what its sign says.
     */
    ADJUSTMENT_DEBIT("Adjustment — driver owes more", +1),

    /** Write-off, rounding residue, or correcting an over-recorded advance. Reduces the obligation. */
    ADJUSTMENT_CREDIT("Adjustment — driver owes less", -1);

    private final String label;
    private final int signum;

    FleetCashDirection(String label, int signum) {
        this.label = label;
        this.signum = signum;
    }

    public String label() {
        return label;
    }

    /** +1 = the driver owes the company more after this entry; -1 = less. Amounts are always positive. */
    public int signum() {
        return signum;
    }

    /** Directions that require a typed reason before they may be written. */
    public boolean requiresReason() {
        return this == ADJUSTMENT_DEBIT || this == ADJUSTMENT_CREDIT || this == RECOVERY;
    }

    /** Money that belongs to a customer, not to the company's float — needs a party/booking reference. */
    public boolean isCustomerMoney() {
        return this == CUSTOMER_COLLECTION || this == COLLECTION_DEPOSIT;
    }
}
