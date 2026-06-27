package com.crm.travelcrm.portal.booking.dto;

import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.enums.PaymentStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Traveler-safe booking detail (single trip). Exposes the customer-facing price breakdown
 * (base + GST + TCS = total payable, and what's paid/pending) and the booked services as a simple
 * itinerary. Deliberately omits {@code vendorCost} and {@code netProfit} (internal margins).
 */
@Data
@Builder
public class TravelerBookingDetailDto {
    private UUID publicId;
    private String bookingCode;
    private String destination;
    private LocalDate bookingDate;
    private LocalDate travelDate;
    private BookingStatus status;
    private PaymentStatus paymentStatus;

    // Customer-facing price breakdown
    private BigDecimal baseAmount;     // Booking.customerAmount
    private BigDecimal gst;
    private BigDecimal tcs;
    private BigDecimal totalPayable;
    private BigDecimal paidAmount;
    private BigDecimal pendingAmount;

    /** Booked services — the traveler-safe "itinerary" view (e.g. Hotel, Transfers, Sightseeing). */
    private List<String> services;
}
