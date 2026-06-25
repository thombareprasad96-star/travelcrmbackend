// ── BookingResponseDTO — ADMIN + MANAGER ──────────────────────────────────────

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
public class BookingResponseDTO {

    // Public UUID — never expose internal Long id to the outside world
    private UUID   publicId;
    private String bookingCode;

    // Customer info
    private Long   customerId;
    private String customerNameSnapshot;   // name as it was at booking time

    // Destination info
    private Long   destinationId;
    private String destinationSnapshot;

    private Long leadId;

    // Traceability — source lead/quotation this booking was converted from (UUIDs, nullable)
    private UUID sourceLeadPublicId;
    private UUID sourceQuotationPublicId;

    // Dates
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate bookingDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate travelDate;

    // ── Financials (full set — ADMIN and MANAGER only) ────────────────────────
    private BigDecimal customerAmount;
    private BigDecimal vendorCost;      // sensitive — not in BookingSummaryDTO
    private BigDecimal gst;
    private BigDecimal tcs;
    private BigDecimal totalPayable;
    private BigDecimal paidAmount;
    private BigDecimal pendingAmount;   // computed in service, not stored
    private BigDecimal netProfit;       // sensitive — not in BookingSummaryDTO

    // Status
    private BookingStatus status;
    private PaymentStatus paymentStatus;

    // Services
    private List<String> services;

    // Audit
    private String createdBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    // ── Removed from your original ────────────────────────────────────────────
    // id (Long)     → replaced by publicId (UUID)
    // customerName  → replaced by customerNameSnapshot
    // destination   → replaced by destinationSnapshot
    // active        → removed, replaced by deletedAt on entity
}