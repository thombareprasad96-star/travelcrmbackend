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

    // ── Assignment — who services this booking ───────────────────────────────
    // publicId + denormalised name of the assignee, resolved through BookingAssigneeView so a
    // paged response costs one query for the whole page. Both null when unassigned (every booking
    // created before this feature) or when the assignee no longer resolves.
    private UUID   assignedUserId;
    private String assignedUserName;

    // Dates
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate bookingDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate travelDate;

    // ── Financials (full set — ADMIN and MANAGER only) ────────────────────────

    /** Overseas tour programme package — drives TCS when the tenant's policy is OVERSEAS_ONLY. */
    private boolean overseasTourPackage;

    private BigDecimal customerAmount;
    private BigDecimal vendorCost;      // sensitive — not in BookingSummaryDTO
    private BigDecimal gst;
    private BigDecimal tcs;
    private BigDecimal totalPayable;
    private BigDecimal paidAmount;
    private BigDecimal pendingAmount;   // computed in service, not stored

    // The agency's own costs on this booking (staff commission, marketing, gateway fees, courier) —
    // the sum of the ACTIVE, INTERNAL-typed expense rows. Supplier cost is NOT in here; that is
    // vendorCost. Exposed so the UI can show the full margin breakdown
    // (customerAmount − vendorCost − totalInternalCosts) instead of inferring the gap.
    private BigDecimal totalInternalCosts;  // sensitive — not in BookingSummaryDTO

    private BigDecimal netProfit;       // sensitive — not in BookingSummaryDTO

    // Gross amount actually refunded to the customer (money OUT), accrued by the refund flow.
    // Exposed so the UI never has to guess a refund figure: before this field existed, three separate
    // screens each invented their own (Σ totalPayable of REFUNDED, Σ paidAmount of "Refunded",
    // and a hardcoded 0). Distinct from paidAmount, which stays the historical gross received.
    private BigDecimal refundedAmount;

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