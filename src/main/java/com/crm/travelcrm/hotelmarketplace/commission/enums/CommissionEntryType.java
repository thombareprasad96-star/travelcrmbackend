package com.crm.travelcrm.hotelmarketplace.commission.enums;

/**
 * What economic event a ledger row records (design doc §5.12).
 *
 * <p>The type never carries the sign — {@code amount} does. A reader summing the column gets the net
 * position with no case analysis, which is precisely the arithmetic that goes wrong the moment it is
 * spread across a reporting query written six months later by someone who forgot that REVERSAL rows
 * are stored positive.</p>
 */
public enum CommissionEntryType {

    /** The platform earned something on a confirmed booking. Positive. Exactly one per booking. */
    ACCRUAL,

    /** A cancellation took some or all of an accrual back. Negative. */
    REVERSAL,

    /** A manual SuperAdmin correction — a mis-keyed approval, a negotiated goodwill write-down. Signed. */
    ADJUSTMENT,

    /**
     * A payout movement against the ledger.
     *
     * <p><b>Nothing writes this yet.</b> In this release settlement is recorded as a status transition
     * on the rows being settled ({@code markSettled}), because there is no payout batch to point a
     * settlement row at. The value exists in the enum and in the table's check constraint so the
     * payout module can start writing rows without a migration or a data backfill.</p>
     */
    SETTLEMENT
}
