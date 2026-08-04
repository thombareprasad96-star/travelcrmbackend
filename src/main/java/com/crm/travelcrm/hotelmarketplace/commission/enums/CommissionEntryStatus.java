package com.crm.travelcrm.hotelmarketplace.commission.enums;

/**
 * How real the money on a ledger row is (design doc §8 Step 9).
 *
 * <p><b>Status is the only mutable column on a ledger row.</b> {@code amount} never moves — a figure
 * that changes retroactively cannot be reconciled against anything that was reported from it. A row
 * whose economics turn out to be wrong is answered with another row, not an edit.</p>
 */
public enum CommissionEntryStatus {

    /** Accrued on approval, but the stay has not happened and the policy window has not closed. */
    PENDING,

    /** The platform has actually earned it — the check-in/policy rule fired. */
    EARNED,

    /** Taken back in full by a cancellation. Terminal: a reversed accrual is never re-earned. */
    REVERSED,

    /** Paid out or netted off against the tenant's account. Terminal. */
    SETTLED;

    /** Whether a status transition may still be applied to a row in this state. */
    public boolean isOpen() {
        return this == PENDING || this == EARNED;
    }
}
