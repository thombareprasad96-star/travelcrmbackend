package com.crm.travelcrm.booking.controller;

import com.crm.travelcrm.booking.dto.request.BulkCreateBookingExpensesRequest;
import com.crm.travelcrm.booking.dto.request.UpdateBookingExpenseRequest;
import com.crm.travelcrm.booking.dto.response.BookingExpenseResponse;
import com.crm.travelcrm.booking.dto.response.BookingExpenseSummaryResponse;
import com.crm.travelcrm.booking.service.BookingExpenseService;
import com.crm.travelcrm.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Expense ledger for a booking — what the agency spent on it and what it still owes its vendors.
 * Supplier/internal cost visibility requires {@code BOOKING_PROFIT_READ}; mutations additionally
 * require {@code BOOKING_UPDATE}.
 *
 * <p>{@code BOOKING_PROFIT_READ} is intentionally separate from {@code BOOKING_READ}: sales users
 * may need the customer-facing booking without being allowed to inspect supplier costs or margin.
 * {@code BookingExpenseServiceImpl} still applies tenant and sub-agent row scope on every operation.
 *
 * <p><b>Second read authority — {@code MARKETPLACE_PAYABLE_READ}.</b> A hotel-marketplace payable is
 * a price the tenant must know in order to quote its customer, not a margin. {@code TRAVEL_AGENT} —
 * the role that actually places marketplace orders — holds this and not {@code BOOKING_PROFIT_READ},
 * so without it an agent can place a platform order and then be unable to see what it costs.
 * It grants strictly less: {@link BookingExpenseServiceImpl#getExpenses} filters the ledger down to
 * rows carrying a {@code marketplaceBookingPublicId}, and every other row stays hidden.
 *
 * <p>The widening is on the CLASS gate, which in Spring Security applies only where no method-level
 * {@code @PreAuthorize} overrides it. Every mutation carries its own, so writes remain
 * {@code BOOKING_UPDATE + BOOKING_PROFIT_READ}. {@code /summary} carries one too, deliberately: it is
 * a whole-ledger rollup with no per-row identity, so it cannot be filtered and would leak the very
 * totals this split exists to withhold.
 */
@RestController
@RequestMapping("/api/bookings/{bookingPublicId}/expenses")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('BOOKING_PROFIT_READ','MARKETPLACE_PAYABLE_READ')")
public class BookingExpenseController {

    private static final Logger log = LogManager.getLogger(BookingExpenseController.class);

    private final BookingExpenseService expenseService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<BookingExpenseResponse>>> list(
            @PathVariable UUID bookingPublicId) {
        log.info("GET /api/bookings/{}/expenses", bookingPublicId);
        return ResponseEntity.ok(ApiResponse.success("Expenses fetched successfully",
                expenseService.getExpenses(bookingPublicId)));
    }

    /**
     * Rollup of the ledger — totals plus the vendor-payable and overdue position.
     *
     * <p>Pinned to {@code BOOKING_PROFIT_READ}: a rollup has no per-row identity, so the marketplace
     * row filter cannot apply to it, and returning whole-ledger totals to a marketplace-only reader
     * would disclose exactly the internal costs that authority is scoped to exclude.
     */
    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('BOOKING_PROFIT_READ')")
    public ResponseEntity<ApiResponse<BookingExpenseSummaryResponse>> summary(
            @PathVariable UUID bookingPublicId) {
        log.info("GET /api/bookings/{}/expenses/summary", bookingPublicId);
        return ResponseEntity.ok(ApiResponse.success("Expense summary fetched successfully",
                expenseService.getSummary(bookingPublicId)));
    }

    /**
     * Bulk create — the expense form saves every row it holds in one click, so the body is
     * {@code { "expenses": [ … ] }} and the whole batch is one transaction.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('BOOKING_UPDATE') and hasAuthority('BOOKING_PROFIT_READ')")
    public ResponseEntity<ApiResponse<List<BookingExpenseResponse>>> add(
            @PathVariable UUID bookingPublicId,
            @Valid @RequestBody BulkCreateBookingExpensesRequest request) {
        log.info("POST /api/bookings/{}/expenses - {} line(s)",
                bookingPublicId, request.getExpenses().size());
        List<BookingExpenseResponse> created = expenseService.addExpenses(bookingPublicId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        created.size() + " expense(s) recorded successfully", created, 201));
    }

    @PutMapping("/{expensePublicId}")
    @PreAuthorize("hasAuthority('BOOKING_UPDATE') and hasAuthority('BOOKING_PROFIT_READ')")
    public ResponseEntity<ApiResponse<BookingExpenseResponse>> update(
            @PathVariable UUID bookingPublicId,
            @PathVariable UUID expensePublicId,
            @Valid @RequestBody UpdateBookingExpenseRequest request) {
        log.info("PUT /api/bookings/{}/expenses/{}", bookingPublicId, expensePublicId);
        return ResponseEntity.ok(ApiResponse.success("Expense updated successfully",
                expenseService.updateExpense(bookingPublicId, expensePublicId, request)));
    }

    @DeleteMapping("/{expensePublicId}")
    @PreAuthorize("hasAuthority('BOOKING_UPDATE') and hasAuthority('BOOKING_PROFIT_READ')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID bookingPublicId,
            @PathVariable UUID expensePublicId) {
        log.info("DELETE /api/bookings/{}/expenses/{}", bookingPublicId, expensePublicId);
        expenseService.deleteExpense(bookingPublicId, expensePublicId);
        return ResponseEntity.ok(ApiResponse.success("Expense removed successfully"));
    }

    /**
     * Undo a delete. POST rather than PUT because it is an action on the row, not a representation
     * of it — and it carries no body. Same BOOKING_UPDATE gate as every other mutation here: a user
     * who may delete an expense may un-delete it.
     */
    @PostMapping("/{expensePublicId}/restore")
    @PreAuthorize("hasAuthority('BOOKING_UPDATE') and hasAuthority('BOOKING_PROFIT_READ')")
    public ResponseEntity<ApiResponse<BookingExpenseResponse>> restore(
            @PathVariable UUID bookingPublicId,
            @PathVariable UUID expensePublicId) {
        log.info("POST /api/bookings/{}/expenses/{}/restore", bookingPublicId, expensePublicId);
        return ResponseEntity.ok(ApiResponse.success("Expense restored successfully",
                expenseService.restoreExpense(bookingPublicId, expensePublicId)));
    }
}
