package com.crm.travelcrm.booking.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Body of {@code POST /api/bookings/preview} — the money inputs of the create form, and nothing
 * else. The endpoint answers "what will the server stamp on this booking if I save it now?", so the
 * create page can show GST / TCS / total / net profit / payment status BEFORE saving without ever
 * computing tax in the browser (this codebase's standing rule: the backend computes every rupee).
 *
 * <p>Deliberately not {@link CreateBookingRequestDTO}: a preview must be answerable while the form
 * is half-filled — before a customer is resolved or a destination picked — so it carries only the
 * fields the figures actually depend on.
 */
@Getter
@Setter
public class BookingFinancialPreviewRequest {

    /** The pre-tax base the customer is being charged. The one input a preview cannot do without. */
    @NotNull(message = "Customer amount is required")
    @DecimalMin(value = "0.01", message = "Customer amount must be greater than 0")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal customerAmount;

    /** Optional — absent is treated as 0, exactly as the create path does. */
    @DecimalMin(value = "0.0", message = "Vendor cost cannot be negative")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal vendorCost;

    /** Advance collected so far. Optional — absent is treated as 0. */
    @DecimalMin(value = "0.0", message = "Paid amount cannot be negative")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal paidAmount;

    /**
     * Whether this is an overseas tour programme package — same semantics as the field on
     * {@link CreateBookingRequestDTO}: consulted only when the tenant's TCS policy is
     * OVERSEAS_ONLY; absent means domestic.
     */
    private Boolean overseasTourPackage;
}
