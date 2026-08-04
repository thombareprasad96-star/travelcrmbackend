package com.crm.travelcrm.booking.controller;


import com.crm.travelcrm.booking.dto.request.BookingFinancialPreviewRequest;
import com.crm.travelcrm.booking.dto.request.CancelBookingRequestDTO;
import com.crm.travelcrm.booking.dto.request.CreateBookingRequestDTO;
import com.crm.travelcrm.booking.dto.request.PaymentUpdateRequestDTO;
import com.crm.travelcrm.booking.dto.request.StatusUpdateRequestDTO;
import com.crm.travelcrm.booking.dto.request.UpdateBookingRequestDTO;
import com.crm.travelcrm.booking.dto.response.BookingFinancialPreviewResponse;
import com.crm.travelcrm.booking.dto.response.BookingPageSummaryResponseDTO;
import com.crm.travelcrm.booking.dto.response.BookingResponseDTO;
import com.crm.travelcrm.booking.dto.response.BookingStatsResponseDTO;
import com.crm.travelcrm.booking.dto.response.BookingTimelineEventDTO;
import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.enums.PaymentStatus;
import com.crm.travelcrm.booking.service.BookingService;
import com.crm.travelcrm.booking.service.BookingTimelineService;

import com.crm.travelcrm.booking.service.CsvExportService;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('BOOKING_READ')")   // class default; mutating methods override below
public class

BookingController {

    private static final Logger log = LogManager.getLogger(BookingController.class);

    private final BookingService bookingService;
    private final BookingTimelineService bookingTimelineService;
    private final CsvExportService csvExportService;


    // ── Create ───────────────────────────────────────────────────────────────


    @PostMapping
    @PreAuthorize("hasAuthority('BOOKING_CREATE')")
    public ResponseEntity<ApiResponse<BookingResponseDTO>> create(
            @Valid @RequestBody CreateBookingRequestDTO request) {
        // Log the mode, never the identifiers — the "new customer" branch carries a name, phone and
        // email straight from the form, and access logs are not the place for a customer's contact
        // details. The service logs the resolved customer CODE once it exists.
        log.info("POST /api/bookings - customer mode: {}",
                request.getCustomer() != null && request.getCustomer().isExistingMode()
                        ? "existing" : "new");
        BookingResponseDTO response = bookingService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Booking created successfully", response, 201));
    }

    /**
     * Dry-run of the money a create would stamp — GST/TCS/total/netProfit/paymentStatus under this
     * tenant's accounting settings. Powers the create form's live computed panel, so the browser
     * never computes tax itself. Gated BOOKING_CREATE (its only consumer is the create form, and
     * the response carries netProfit). Deliberately not logged per call: it fires on every
     * debounced keystroke of the amount fields.
     */
    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('BOOKING_CREATE')")
    public ResponseEntity<ApiResponse<BookingFinancialPreviewResponse>> previewFinancials(
            @Valid @RequestBody BookingFinancialPreviewRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Booking financials computed", bookingService.previewFinancials(request)));
    }

    // Lead → Booking conversion lives on the lead-centric path and is handled by
    // LeadConversionController (POST /api/leads/{publicId}/convert-to-booking).

    // ── Get All (Paginated) ──────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<PagedApiResponse<BookingResponseDTO>> getAll(
            @RequestParam(defaultValue = "0")    int    page,
            @RequestParam(defaultValue = "10")   int    size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        log.info("GET /api/bookings - page: {}, size: {}", page, size);
        return ResponseEntity.ok(bookingService.getAll(page, size, sortBy, sortDir));
    }

    // ── Get by ID ────────────────────────────────────────────────────────────

    @GetMapping("/{publicId}")
    public ResponseEntity<ApiResponse<BookingResponseDTO>> getById(@PathVariable UUID publicId) {
        log.info("GET /api/bookings/{}", publicId);
        return ResponseEntity.ok(ApiResponse.success("Booking fetched successfully",
                bookingService.getById(publicId)));
    }

    /** Chronological operational audit used by the Booking Details Activity tab. */
    @GetMapping("/{publicId}/timeline")
    public ResponseEntity<ApiResponse<List<BookingTimelineEventDTO>>> getTimeline(
            @PathVariable UUID publicId) {
        log.info("GET /api/bookings/{}/timeline", publicId);
        return ResponseEntity.ok(ApiResponse.success("Booking timeline fetched successfully",
                bookingTimelineService.getTimeline(publicId)));
    }

    // ── Get by Code ──────────────────────────────────────────────────────────

    @GetMapping("/code/{code}")
    public ResponseEntity<ApiResponse<BookingResponseDTO>> getByCode(@PathVariable String code) {
        log.info("GET /api/bookings/code/{}", code);
        return ResponseEntity.ok(ApiResponse.success("Booking fetched successfully",
                bookingService.getByCode(code)));
    }

    // ── Update ───────────────────────────────────────────────────────────────

    @PutMapping("/{publicId}")
    @PreAuthorize("hasAuthority('BOOKING_UPDATE')")
    public ResponseEntity<ApiResponse<BookingResponseDTO>> update(
            @PathVariable UUID publicId,
            @Valid @RequestBody UpdateBookingRequestDTO request) {
        log.info("PUT /api/bookings/{}", publicId);
        return ResponseEntity.ok(ApiResponse.success("Booking updated successfully",
                bookingService.update(publicId, request)));
    }

    // ── Update Status ────────────────────────────────────────────────────────

    @PatchMapping("/{publicId}/status")
    @PreAuthorize("hasAuthority('BOOKING_UPDATE')")
    public ResponseEntity<ApiResponse<BookingResponseDTO>> updateStatus(
            @PathVariable UUID publicId,
            @Valid @RequestBody StatusUpdateRequestDTO request) {
        log.info("PATCH /api/bookings/{}/status - status: {}", publicId, request.getStatus());
        return ResponseEntity.ok(ApiResponse.success("Booking status updated successfully",
                bookingService.updateStatus(publicId, request)));
    }

    // ── Cancel (with explicit lead handling) ──────────────────────────────────

    @PostMapping("/{publicId}/cancel")
    @PreAuthorize("hasAuthority('BOOKING_CANCEL')")
    public ResponseEntity<ApiResponse<BookingResponseDTO>> cancel(
            @PathVariable UUID publicId,
            @Valid @RequestBody CancelBookingRequestDTO request) {
        log.info("POST /api/bookings/{}/cancel - action: {}", publicId, request.getAction());
        return ResponseEntity.ok(ApiResponse.success("Booking cancelled successfully",
                bookingService.cancel(publicId, request)));
    }

    // ── Update Payment ───────────────────────────────────────────────────────

    @PatchMapping("/{publicId}/payment")
    @PreAuthorize("hasAuthority('BOOKING_UPDATE')")
    public ResponseEntity<ApiResponse<BookingResponseDTO>> updatePayment(
            @PathVariable UUID publicId,
            @Valid @RequestBody PaymentUpdateRequestDTO request) {
        log.info("PATCH /api/bookings/{}/payment - paidAmount: {}", publicId, request.getAmount());
        return ResponseEntity.ok(ApiResponse.success("Payment updated successfully",
                bookingService.updatePayment(publicId, request)));
    }

    // ── Soft Delete ──────────────────────────────────────────────────────────

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAuthority('BOOKING_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID publicId) {
        log.info("DELETE /api/bookings/{}", publicId);
        bookingService.delete(publicId);
        return ResponseEntity.ok(ApiResponse.success("Booking deleted successfully"));
    }

    // ── Get by Customer ──────────────────────────────────────────────────────

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<ApiResponse<List<BookingResponseDTO>>> getByCustomer(
            @PathVariable Long customerId) {
        log.info("GET /api/bookings/customer/{}", customerId);
        return ResponseEntity.ok(ApiResponse.success("Bookings fetched successfully",
                bookingService.getByCustomerId(customerId)));
    }

    // ── Search ───────────────────────────────────────────────────────────────

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<BookingResponseDTO>>> search(
            @RequestParam String keyword) {
        log.info("GET /api/bookings/search - keyword: {}", keyword);
        return ResponseEntity.ok(ApiResponse.success("Search results fetched successfully",
                bookingService.search(keyword)));
    }

    // ── Filter ───────────────────────────────────────────────────────────────

    @GetMapping("/filter")
    public ResponseEntity<ApiResponse<List<BookingResponseDTO>>> filter(
            @RequestParam(required = false) BookingStatus status,
            @RequestParam(required = false) PaymentStatus paymentStatus,
            @RequestParam(required = false) Integer bookingMonth,
            @RequestParam(required = false) Integer travelMonth,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount) {
        log.info("GET /api/bookings/filter");
        return ResponseEntity.ok(ApiResponse.success("Filtered bookings fetched successfully",
                bookingService.filter(status, paymentStatus, bookingMonth, travelMonth,
                        customerId, fromDate, toDate, minAmount, maxAmount)));
    }

    // ── Stats ────────────────────────────────────────────────────────────────

    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('CRM_FULL')")   // tenant-wide financial aggregate — blocks sub-agents (they hold no CRM_FULL)
    public ResponseEntity<ApiResponse<BookingStatsResponseDTO>> getStats() {
        log.info("GET /api/bookings/stats");
        return ResponseEntity.ok(ApiResponse.success("Stats fetched successfully",
                bookingService.getStats()));
    }

    // ── Page Summary ─────────────────────────────────────────────────────────
    @GetMapping("/page-summary")
    @PreAuthorize("hasAuthority('CRM_FULL')")   // money summary (profit/gst/tcs) — blocks sub-agents
    public ResponseEntity<ApiResponse<BookingPageSummaryResponseDTO>> getPageSummary(
            @RequestParam(defaultValue = "0")    int    page,
            @RequestParam(defaultValue = "10")   int    size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        log.info("GET /api/bookings/page-summary");
        return ResponseEntity.ok(ApiResponse.success("Page summary fetched successfully",
                bookingService.getPageSummary(page, size, sortBy, sortDir)));
    }

    // ── Export CSV ──────────────────────────────────────────────────────────


    @GetMapping("/export")
    @PreAuthorize("hasAuthority('CRM_FULL')")   // full-tenant CSV export — blocks sub-agents
    public ResponseEntity<byte[]> export() {
        log.info("GET /api/bookings/export");
        byte[] csv = csvExportService.exportBookings();
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=bookings.csv")
                .header("Content-Type", "text/csv")
                .body(csv);
    }

    // ── Send Voucher ─────────────────────────────────────────────────────────


    @PostMapping("/{publicId}/send-voucher")
    @PreAuthorize("hasAuthority('BOOKING_UPDATE')")
    public ResponseEntity<ApiResponse<Void>> sendVoucher(@PathVariable UUID publicId) {
        log.info("POST /api/bookings/{}/send-voucher", publicId);
        bookingService.sendVoucher(publicId);
        return ResponseEntity.ok(ApiResponse.success("Voucher sent successfully"));
    }
}
