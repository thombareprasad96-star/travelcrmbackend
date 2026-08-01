package com.crm.travelcrm.booking.enums;

/**
 * What KIND of cost an expense line represents — and therefore whether it belongs in the booking's
 * profit calculation.
 *
 * <p>This is the discriminator that makes the profit formula safe. {@code Booking.vendorCost} is a
 * single figure the agent types on the booking itself, and it already represents everything paid to
 * suppliers. The expense ledger, meanwhile, carries BOTH kinds of line: its category list ships
 * "Hotel"/"Flight"/"Transport"/"Visa" (supplier cost) alongside "Commission"/"Office Expense"
 * (agency overhead). Summing the ledger wholesale into
 * {@code netProfit = customerAmount − vendorCost − totalInternalCosts} would therefore subtract the
 * supplier cost TWICE — once as the typed {@code vendorCost}, once again as an expense row — and a
 * profitable booking would report a loss.
 *
 * <p>So only {@link #INTERNAL} rows feed {@code totalInternalCosts}. {@link #VENDOR} rows stay what
 * they always were: an itemised cash book sitting alongside the typed {@code vendorCost}, reported
 * by {@code GET /api/bookings/{id}/expenses/summary} and never folded into the booking's totals.
 * That preserves the original "expenses do not mutate booking totals" rule for exactly the rows it
 * was written to protect, while letting genuine overhead reach the margin.
 *
 * <p><b>Legacy rows default to {@link #VENDOR}</b> (both the entity default and the column default),
 * so every booking that existed before this feature keeps {@code totalInternalCosts = 0} and its
 * {@code netProfit} is unchanged to the paisa. Reclassifying a line to {@link #INTERNAL} is an
 * explicit act by the user, and recalculates that booking's profit when it happens.
 */
public enum ExpenseCostType {

    /**
     * Money owed to or paid to a supplier for delivering the trip — hotel, flight, transfer, visa,
     * guide, DMC. Already accounted for by {@code Booking.vendorCost}, so these rows are EXCLUDED
     * from {@code totalInternalCosts} and never affect {@code netProfit}.
     */
    VENDOR,

    /**
     * The agency's own cost of doing this booking, over and above what it paid suppliers — staff
     * commission, marketing attributed to the booking, payment-gateway fees, documentation and
     * courier, and other adjustments. Not part of {@code vendorCost}, so these rows ARE summed into
     * {@code totalInternalCosts} and reduce {@code netProfit}.
     */
    INTERNAL
}
