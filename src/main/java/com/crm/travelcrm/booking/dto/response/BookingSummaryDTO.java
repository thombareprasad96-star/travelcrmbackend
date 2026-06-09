// ── BookingSummaryDTO — AGENT role (no sensitive financials) ──────────────────

package com.crm.travelcrm.booking.dto.response;

import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.enums.PaymentStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingSummaryDTO {

    private UUID   publicId;
    private String bookingCode;
    private String customerNameSnapshot;
    private String destinationSnapshot;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate bookingDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate travelDate;

    // ── Restricted financials — what an agent needs to do their job ───────────
    private BigDecimal customerAmount;  // what the customer owes
    private BigDecimal totalPayable;
    private BigDecimal paidAmount;
    private BigDecimal pendingAmount;
    // vendorCost → absent by design
    // netProfit  → absent by design

    private BookingStatus status;
    private PaymentStatus paymentStatus;
    private List<String>  services;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}