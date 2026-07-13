package com.crm.travelcrm.booking.controller;

import com.crm.travelcrm.booking.dto.request.CreateBookingPaymentRequest;
import com.crm.travelcrm.booking.dto.response.BookingPaymentResponse;
import com.crm.travelcrm.booking.service.BookingPaymentService;
import com.crm.travelcrm.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Payment ledger for a booking. Reads require {@code BOOKING_READ}; mutations {@code BOOKING_UPDATE}.
 * Every add/delete keeps the parent booking's {@code paidAmount}/{@code paymentStatus} in step.
 */
@RestController
@RequestMapping("/api/bookings/{bookingPublicId}/payments")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('BOOKING_READ')")
public class BookingPaymentController {

    private static final Logger log = LogManager.getLogger(BookingPaymentController.class);

    private final BookingPaymentService paymentService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<BookingPaymentResponse>>> list(
            @PathVariable UUID bookingPublicId) {
        log.info("GET /api/bookings/{}/payments", bookingPublicId);
        return ResponseEntity.ok(ApiResponse.success("Payments fetched successfully",
                paymentService.getPayments(bookingPublicId)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('BOOKING_UPDATE')")
    public ResponseEntity<ApiResponse<BookingPaymentResponse>> add(
            @PathVariable UUID bookingPublicId,
            @Valid @RequestBody CreateBookingPaymentRequest request) {
        log.info("POST /api/bookings/{}/payments - amount: {}", bookingPublicId, request.getAmount());
        BookingPaymentResponse response = paymentService.addPayment(bookingPublicId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Payment recorded successfully", response, 201));
    }

    @DeleteMapping("/{paymentPublicId}")
    @PreAuthorize("hasAuthority('BOOKING_UPDATE')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID bookingPublicId,
            @PathVariable UUID paymentPublicId) {
        log.info("DELETE /api/bookings/{}/payments/{}", bookingPublicId, paymentPublicId);
        paymentService.deletePayment(bookingPublicId, paymentPublicId);
        return ResponseEntity.ok(ApiResponse.success("Payment removed successfully"));
    }
}