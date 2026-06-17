package com.crm.travelcrm.customer.dto.response;

import com.crm.travelcrm.booking.enums.BookingStatus;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of a customer's booking history for
 * {@code GET /api/customers/{id}/bookings}. Shaped to match the booking-history
 * cards rendered in the customer "View" modal ({@code code, dest, date, amt, status}).
 */
@Value
@Builder
public class CustomerBookingResponse {

    /** Booking publicId — external identifier. */
    UUID id;

    /** Booking code, e.g. {@code BK9001} (UI field {@code code}). */
    String code;

    /** Destination snapshot (UI field {@code dest}). */
    String dest;

    /** Booking date (UI field {@code date}). */
    LocalDate date;

    /** Customer-facing amount (UI field {@code amt}). */
    BigDecimal amt;

    BookingStatus status;
}