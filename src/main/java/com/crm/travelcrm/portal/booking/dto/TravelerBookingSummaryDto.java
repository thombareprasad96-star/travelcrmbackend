package com.crm.travelcrm.portal.booking.dto;

import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.enums.PaymentStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Traveler-safe booking summary for the "my trips" list. Whitelisted fields only — never vendor
 * cost, net profit, internal notes, lead/quotation internals, or any other customer's data.
 */
@Data
@Builder
public class TravelerBookingSummaryDto {
    private UUID publicId;
    private String bookingCode;
    private String destination;
    private LocalDate bookingDate;
    private LocalDate travelDate;
    private BookingStatus status;
    private PaymentStatus paymentStatus;
    private BigDecimal totalPayable;
    private BigDecimal paidAmount;
    private BigDecimal pendingAmount;
}
