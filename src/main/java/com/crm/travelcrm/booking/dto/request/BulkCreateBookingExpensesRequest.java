package com.crm.travelcrm.booking.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * The body of {@code POST /api/bookings/{publicId}/expenses}.
 *
 * <p>Unlike its siblings — {@code addPayment} and {@code addService} both post a single flat
 * object — the expense form is a multi-row ledger and saves every row it holds in one click, so
 * this endpoint is a BULK create and the wrapper shape {@code { "expenses": [ … ] }} is what the
 * client already sends.
 *
 * <p>The whole batch is one transaction: if any row fails validation, none is written. That is the
 * behaviour the "Save 3 Entries" button implies — a half-saved ledger with no indication of which
 * rows landed would be worse than an outright failure.
 */
@Getter
@Setter
public class BulkCreateBookingExpensesRequest {

    /**
     * {@code @Valid} is what cascades bean validation into each element — without it the nested
     * constraints on {@link CreateBookingExpenseRequest} are never evaluated and a row with a null
     * amount reaches the service.
     *
     * <p>The upper bound is a payload guard, not a product limit: the form adds rows one at a time
     * and nobody enters fifty by hand, but the endpoint is public API and each row is an INSERT.
     */
    @Valid
    @NotEmpty(message = "At least one expense is required")
    @Size(max = 50, message = "Cannot save more than 50 expenses at once")
    private List<CreateBookingExpenseRequest> expenses;
}
