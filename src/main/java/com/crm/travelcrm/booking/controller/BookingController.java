package com.crm.travelcrm.booking.controller;


import com.crm.travelcrm.booking.dto.request.CreateBookingRequestDTO;
import com.crm.travelcrm.booking.dto.request.PaymentUpdateRequestDTO;
import com.crm.travelcrm.booking.dto.request.StatusUpdateRequestDTO;
import com.crm.travelcrm.booking.dto.request.UpdateBookingRequestDTO;
import com.crm.travelcrm.booking.dto.response.BookingPageSummaryResponseDTO;
import com.crm.travelcrm.booking.dto.response.BookingResponseDTO;
import com.crm.travelcrm.booking.dto.response.BookingStatsResponseDTO;
import com.crm.travelcrm.booking.enums.BookingStatus;
import com.crm.travelcrm.booking.enums.PaymentStatus;
import com.crm.travelcrm.booking.service.BookingService;

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
@PreAuthorize("hasAuthority('CRM_FULL')")
public class BookingController {

    private static final Logger log = LogManager.getLogger(BookingController.class);

    private final BookingService bookingService;
    private final CsvExportService csvExportService;


    // ── Create ───────────────────────────────────────────────────────────────


    @PostMapping
    public ResponseEntity<ApiResponse<BookingResponseDTO>> create(
            @Valid @RequestBody CreateBookingRequestDTO request) {
        log.info("POST /api/bookings - customer: {}", request.getCustomerId());
        BookingResponseDTO response = bookingService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Booking created successfully", response, 201));
    }

    // ── Create from Lead ─────────────────────────────────────────────────────

    @PostMapping("/from-lead/{leadId}")
    public ResponseEntity<ApiResponse<BookingResponseDTO>> createFromLead(
            @PathVariable Long leadId) {
        log.info("POST /api/bookings/from-lead/{}", leadId);
        BookingResponseDTO response = bookingService.createFromLead(leadId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Booking created from lead successfully", response, 201));
    }

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

    // ── Get by Code ──────────────────────────────────────────────────────────

    @GetMapping("/code/{code}")
    public ResponseEntity<ApiResponse<BookingResponseDTO>> getByCode(@PathVariable String code) {
        log.info("GET /api/bookings/code/{}", code);
        return ResponseEntity.ok(ApiResponse.success("Booking fetched successfully",
                bookingService.getByCode(code)));
    }

    // ── Update ───────────────────────────────────────────────────────────────

    @PutMapping("/{publicId}")
    public ResponseEntity<ApiResponse<BookingResponseDTO>> update(
            @PathVariable UUID publicId,
            @Valid @RequestBody UpdateBookingRequestDTO request) {
        log.info("PUT /api/bookings/{}", publicId);
        return ResponseEntity.ok(ApiResponse.success("Booking updated successfully",
                bookingService.update(publicId, request)));
    }

    // ── Update Status ────────────────────────────────────────────────────────

    @PatchMapping("/{publicId}/status")
    public ResponseEntity<ApiResponse<BookingResponseDTO>> updateStatus(
            @PathVariable UUID publicId,
            @Valid @RequestBody StatusUpdateRequestDTO request) {
        log.info("PATCH /api/bookings/{}/status - status: {}", publicId, request.getStatus());
        return ResponseEntity.ok(ApiResponse.success("Booking status updated successfully",
                bookingService.updateStatus(publicId, request)));
    }

    // ── Update Payment ───────────────────────────────────────────────────────

    @PatchMapping("/{publicId}/payment")
    public ResponseEntity<ApiResponse<BookingResponseDTO>> updatePayment(
            @PathVariable UUID publicId,
            @Valid @RequestBody PaymentUpdateRequestDTO request) {
        log.info("PATCH /api/bookings/{}/payment - paidAmount: {}", publicId, request.getAmount());
        return ResponseEntity.ok(ApiResponse.success("Payment updated successfully",
                bookingService.updatePayment(publicId, request)));
    }

    // ── Soft Delete ──────────────────────────────────────────────────────────

    @DeleteMapping("/{publicId}")
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
    public ResponseEntity<ApiResponse<BookingStatsResponseDTO>> getStats() {
        log.info("GET /api/bookings/stats");
        return ResponseEntity.ok(ApiResponse.success("Stats fetched successfully",
                bookingService.getStats()));
    }

    // ── Page Summary ─────────────────────────────────────────────────────────
    @GetMapping("/page-summary")
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
    public ResponseEntity<ApiResponse<Void>> sendVoucher(@PathVariable UUID publicId) {
        log.info("POST /api/bookings/{}/send-voucher", publicId);
        bookingService.sendVoucher(publicId);
        return ResponseEntity.ok(ApiResponse.success("Voucher sent successfully"));
    }
}