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
 * Reads require {@code BOOKING_READ}; mutations {@code BOOKING_UPDATE}.
 *
 * <p><b>On the choice of permission.</b> These are cost figures, and the Accounting block of the
 * {@code Permission} enum states that finance data is "tenant-wide (admin/accountant), never
 * sub-agent row-scoped" — which would argue for {@code ACCOUNTING_TDS_*}. It is deliberately NOT
 * used here, for two reasons. Booking-level cost is already a {@code BOOKING_READ} surface:
 * {@code BookingResponseDTO} exposes {@code vendorCost} and {@code netProfit}, and
 * {@code BookingServiceItem} exposes a per-line {@code vendorCost}, both behind exactly these two
 * keys. And the ledger button on the bookings grid is gated on {@code BOOKING_UPDATE}, so an
 * {@code ACCOUNTING_*} gate would show a travel agent a button that then 403s. The tenant-wide
 * accounting surface for vendor money remains {@code VendorPayableController}, which is where a
 * bill with TDS and GST belongs; this is the fast per-booking cash book.
 *
 * <p>Sub-agents hold {@code BOOKING_READ}/{@code BOOKING_UPDATE} by default, so the authority
 * check alone does not confine them to their own bookings — {@code BookingExpenseServiceImpl}
 * applies {@code SubAgentScope} on every operation for that.
 */
@RestController
@RequestMapping("/api/bookings/{bookingPublicId}/expenses")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('BOOKING_READ')")
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

    /** Rollup of the ledger — totals plus the vendor-payable and overdue position. */
    @GetMapping("/summary")
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
    @PreAuthorize("hasAuthority('BOOKING_UPDATE')")
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
    @PreAuthorize("hasAuthority('BOOKING_UPDATE')")
    public ResponseEntity<ApiResponse<BookingExpenseResponse>> update(
            @PathVariable UUID bookingPublicId,
            @PathVariable UUID expensePublicId,
            @Valid @RequestBody UpdateBookingExpenseRequest request) {
        log.info("PUT /api/bookings/{}/expenses/{}", bookingPublicId, expensePublicId);
        return ResponseEntity.ok(ApiResponse.success("Expense updated successfully",
                expenseService.updateExpense(bookingPublicId, expensePublicId, request)));
    }

    @DeleteMapping("/{expensePublicId}")
    @PreAuthorize("hasAuthority('BOOKING_UPDATE')")
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
    @PreAuthorize("hasAuthority('BOOKING_UPDATE')")
    public ResponseEntity<ApiResponse<BookingExpenseResponse>> restore(
            @PathVariable UUID bookingPublicId,
            @PathVariable UUID expensePublicId) {
        log.info("POST /api/bookings/{}/expenses/{}/restore", bookingPublicId, expensePublicId);
        return ResponseEntity.ok(ApiResponse.success("Expense restored successfully",
                expenseService.restoreExpense(bookingPublicId, expensePublicId)));
    }
}
