package com.crm.travelcrm.booking.controller;

import com.crm.travelcrm.booking.dto.request.AssignVendorRequest;
import com.crm.travelcrm.booking.dto.request.CreateBookingServiceItemRequest;
import com.crm.travelcrm.booking.dto.request.UpdateBookingServiceItemRequest;
import com.crm.travelcrm.booking.dto.response.BookingServiceItemResponse;
import com.crm.travelcrm.booking.service.BookingDocumentService;
import com.crm.travelcrm.booking.service.BookingServiceItemService;
import com.crm.travelcrm.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Detailed service lines on a booking, with vendor assignment and a per-line voucher.
 * Reads require {@code BOOKING_READ}; mutations {@code BOOKING_UPDATE}.
 */
@RestController
@RequestMapping("/api/bookings/{bookingPublicId}/services")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('BOOKING_READ')")
public class BookingServiceItemController {

    private static final Logger log = LogManager.getLogger(BookingServiceItemController.class);

    private final BookingServiceItemService serviceItemService;
    private final BookingDocumentService    documentService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<BookingServiceItemResponse>>> list(
            @PathVariable UUID bookingPublicId) {
        log.info("GET /api/bookings/{}/services", bookingPublicId);
        return ResponseEntity.ok(ApiResponse.success("Services fetched successfully",
                serviceItemService.getServiceItems(bookingPublicId)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('BOOKING_UPDATE')")
    public ResponseEntity<ApiResponse<BookingServiceItemResponse>> add(
            @PathVariable UUID bookingPublicId,
            @Valid @RequestBody CreateBookingServiceItemRequest request) {
        log.info("POST /api/bookings/{}/services - {}", bookingPublicId, request.getTitle());
        BookingServiceItemResponse response = serviceItemService.addServiceItem(bookingPublicId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Service added successfully", response, 201));
    }

    @PutMapping("/{serviceItemPublicId}")
    @PreAuthorize("hasAuthority('BOOKING_UPDATE')")
    public ResponseEntity<ApiResponse<BookingServiceItemResponse>> update(
            @PathVariable UUID bookingPublicId,
            @PathVariable UUID serviceItemPublicId,
            @Valid @RequestBody UpdateBookingServiceItemRequest request) {
        log.info("PUT /api/bookings/{}/services/{}", bookingPublicId, serviceItemPublicId);
        return ResponseEntity.ok(ApiResponse.success("Service updated successfully",
                serviceItemService.updateServiceItem(bookingPublicId, serviceItemPublicId, request)));
    }

    @DeleteMapping("/{serviceItemPublicId}")
    @PreAuthorize("hasAuthority('BOOKING_UPDATE')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID bookingPublicId,
            @PathVariable UUID serviceItemPublicId) {
        log.info("DELETE /api/bookings/{}/services/{}", bookingPublicId, serviceItemPublicId);
        serviceItemService.deleteServiceItem(bookingPublicId, serviceItemPublicId);
        return ResponseEntity.ok(ApiResponse.success("Service removed successfully"));
    }

    @PutMapping("/{serviceItemPublicId}/vendor")
    @PreAuthorize("hasAuthority('BOOKING_UPDATE')")
    public ResponseEntity<ApiResponse<BookingServiceItemResponse>> assignVendor(
            @PathVariable UUID bookingPublicId,
            @PathVariable UUID serviceItemPublicId,
            @Valid @RequestBody AssignVendorRequest request) {
        log.info("PUT /api/bookings/{}/services/{}/vendor -> {}",
                bookingPublicId, serviceItemPublicId, request.getVendorPublicId());
        return ResponseEntity.ok(ApiResponse.success("Vendor assigned successfully",
                serviceItemService.assignVendor(bookingPublicId, serviceItemPublicId, request)));
    }

    /** Per-service confirmation voucher (PDF, on-the-fly). */
    @GetMapping("/{serviceItemPublicId}/voucher")
    public ResponseEntity<byte[]> serviceVoucher(
            @PathVariable UUID bookingPublicId,
            @PathVariable UUID serviceItemPublicId) {
        log.info("GET /api/bookings/{}/services/{}/voucher", bookingPublicId, serviceItemPublicId);
        byte[] pdf = documentService.renderServiceVoucher(bookingPublicId, serviceItemPublicId);
        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=service-voucher.pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}