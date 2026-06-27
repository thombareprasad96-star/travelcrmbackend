package com.crm.travelcrm.portal.booking.dto;

import com.crm.travelcrm.booking.enums.PaymentStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Traveler-safe payment status for a booking. Itemised transaction history will be sourced from the
 * Payments module once it exists; today this is the authoritative paid/pending snapshot.
 */
@Data
@Builder
public class TravelerPaymentStatusDto {
    private UUID bookingPublicId;
    private String bookingCode;
    private BigDecimal totalPayable;
    private BigDecimal paidAmount;
    private BigDecimal pendingAmount;
    private PaymentStatus paymentStatus;
}
