package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.booking.dto.request.BulkCreateBookingExpensesRequest;
import com.crm.travelcrm.booking.dto.request.UpdateBookingExpenseRequest;
import com.crm.travelcrm.booking.dto.response.BookingExpenseResponse;
import com.crm.travelcrm.booking.dto.response.BookingExpenseSummaryResponse;

import java.util.List;
import java.util.UUID;

/**
 * The per-booking expense ledger — what the agency spent on a booking and what it still owes.
 *
 * <p>Every method takes the booking's {@code publicId} and resolves it through the same guarded
 * lookup, so a booking the caller cannot see is a 404 on every operation, not just on read.
 */
public interface BookingExpenseService {

    /** The booking's expense lines, most recent cost first. */
    List<BookingExpenseResponse> getExpenses(UUID bookingPublicId);

    /**
     * Records every line in the request as one transaction — all rows are written or none is.
     *
     * @return the created lines with server-settled money figures, in submission order
     */
    List<BookingExpenseResponse> addExpenses(UUID bookingPublicId, BulkCreateBookingExpensesRequest request);

    /** Patch one line; supplied money fields are re-settled as a unit. */
    BookingExpenseResponse updateExpense(UUID bookingPublicId, UUID expensePublicId,
                                         UpdateBookingExpenseRequest request);

    /** Soft-delete one line. Recalculates the booking's profit if the line was INTERNAL. */
    void deleteExpense(UUID bookingPublicId, UUID expensePublicId);

    /**
     * Undo a soft-delete — the mirror of {@link #deleteExpense}. 409s when the line is not actually
     * deleted, so a double-restore is a clear error rather than a silent no-op.
     *
     * <p>Deliberately an inline undo on the expense screen rather than a {@code TrashableType}
     * registration: an expense is a booking sub-resource, it has no name for the trash grid to
     * label rows with, and the universal purge would have to reason about a child whose parent
     * booking may itself be trashed.
     */
    BookingExpenseResponse restoreExpense(UUID bookingPublicId, UUID expensePublicId);

    /** Totals and payable position across the booking's ledger. */
    BookingExpenseSummaryResponse getSummary(UUID bookingPublicId);
}
