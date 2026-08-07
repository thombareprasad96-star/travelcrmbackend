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
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class BookingResponseDTO {

    // Public UUID — never expose internal Long id to the outside world
    private UUID   publicId;
    private String bookingCode;

    // Customer info
    /**
     * The customer's publicId — was an internal {@code Long}, despite the comment above promising
     * otherwise. That leak also broke the booking detail screen's customer card: it fetches
     * {@code GET /api/customers/{id}}, which takes a UUID, and the booking only ever handed it a
     * number no endpoint accepts.
     */
    private UUID   customerId;
    private String customerNameSnapshot;   // name as it was at booking time

    // Destination info
    private UUID   destinationId;
    private String destinationSnapshot;

    /** Source lead's publicId. Same reason as {@code customerId} — a Long here is unusable by the UI. */
    private UUID leadId;

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

    // The supplier vendorCost is owed to. Name is a snapshot taken at save, so it still renders after
    // the vendor is renamed or soft-deleted; publicId is what the edit form re-selects with.
    private java.util.UUID vendorPublicId;   // sensitive — not in BookingSummaryDTO
    private String vendorName;               // sensitive — not in BookingSummaryDTO
    private BigDecimal gst;
    private BigDecimal tcs;
    private BigDecimal totalPayable;
    private BigDecimal paidAmount;
    private BigDecimal pendingAmount;   // computed in service, not stored

    // The agency's own costs on this booking (staff commission, marketing, gateway fees, courier) —
    // the sum of the ACTIVE, INTERNAL-typed expense rows.
    private BigDecimal totalInternalCosts;  // sensitive — not in BookingSummaryDTO

    // Supplier cost the agency ITEMISED through the expense ledger — the sum of the ACTIVE,
    // VENDOR-typed rows, excluding hotel-marketplace payables (those are already inside vendorCost).
    // Additive with vendorCost, never a restatement of it.
    private BigDecimal totalVendorCosts;    // sensitive — not in BookingSummaryDTO

    // vendorCost + totalVendorCosts. Sent pre-computed rather than left to the client because three
    // screens previously each re-derived a margin figure and drifted; the backend is the only thing
    // that gets to decide what a booking cost.
    private BigDecimal totalSupplierCost;   // sensitive — not in BookingSummaryDTO

    // customerAmount − vendorCost − totalVendorCosts − totalInternalCosts. The four terms above are
    // all exposed so the UI can SHOW that subtraction rather than present an unexplained number.
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

    /** Traveller / departure / itinerary detail. Null on bookings taken without it. */
    private TripSnapshotResponse tripSnapshot;

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