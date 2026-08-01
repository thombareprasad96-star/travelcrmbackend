package com.crm.travelcrm.booking.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * The rollup of a booking's expense ledger, served by
 * {@code GET /api/bookings/{publicId}/expenses/summary}.
 *
 * <p>This endpoint exists because the expenses deliberately do NOT touch
 * {@code Booking.vendorCost} / {@code Booking.netProfit} (see {@code BookingExpense}'s javadoc for
 * why). Anything that wants "what did this booking actually cost us, and what do we still owe"
 * reads it from here rather than inferring it from the booking's own figures.
 *
 * <p>Every field is computed from the live rows on each call — nothing is cached or stored, so no
 * counter can drift out of step with the ledger it summarises.
 */
@Getter
@Builder
public class BookingExpenseSummaryResponse {

    /** Sum of every expense amount on the booking, both cost types. */
    private BigDecimal totalExpense;

    /**
     * The VENDOR-typed slice of {@link #totalExpense} — itemised supplier cost. Reported only;
     * deliberately NOT folded into {@code Booking.vendorCost}, which stays the figure the agent
     * typed. A gap between the two is meaningful (the ledger is itemised actuals, the booking field
     * is the planned/typed total), so the UI can show both without either overwriting the other.
     */
    private BigDecimal totalVendorExpense;

    /**
     * The INTERNAL-typed slice of {@link #totalExpense} — the agency's own overhead. This IS the
     * {@code totalInternalCosts} term inside {@code Booking.netProfit}, recomputed from the same
     * rows, so the summary and the stored margin always agree.
     */
    private BigDecimal totalInternalCosts;

    /** Sum of what has actually been disbursed. */
    private BigDecimal totalPaid;

    /** Sum of what is still payable to vendors — {@code totalExpense − totalPaid}. */
    private BigDecimal totalOutstanding;

    /** How much of {@link #totalOutstanding} is on lines whose due date has already passed. */
    private BigDecimal overdueOutstanding;

    /** Number of expense lines on the booking. */
    private long expenseCount;

    /** How many of those still carry a balance. */
    private long unsettledCount;

    /** How many carry a balance AND are past their due date. */
    private long overdueCount;
}
