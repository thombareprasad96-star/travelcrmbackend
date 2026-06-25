package com.crm.travelcrm.booking.controller;

import com.crm.travelcrm.booking.dto.request.LeadConversionRequestDTO;
import com.crm.travelcrm.booking.dto.response.BookingResponseDTO;
import com.crm.travelcrm.booking.service.BookingService;
import com.crm.travelcrm.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Lead → Booking conversion endpoint. Lives on the lead-centric URL
 * ({@code /api/leads/{publicId}/convert-to-booking}) but sits in the booking module so the
 * dependency direction stays booking → lead (the lead module never imports booking).
 *
 * <p>Gated by {@code BOOKING_CREATE}: a user without it gets the framework's 403, surfaced by
 * {@code GlobalExceptionHandler} as the friendly "contact your administrator" envelope. Row-level
 * lead visibility is enforced inside the service via {@code LeadAccessGuard}.</p>
 */
@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
public class LeadConversionController {

    private static final Logger log = LogManager.getLogger(LeadConversionController.class);

    private final BookingService bookingService;

    @PostMapping("/{publicId}/convert-to-booking")
    @PreAuthorize("hasAuthority('BOOKING_CREATE')")
    public ResponseEntity<ApiResponse<BookingResponseDTO>> convertToBooking(
            @PathVariable UUID publicId,
            @Valid @RequestBody LeadConversionRequestDTO request) {
        log.info("POST /api/leads/{}/convert-to-booking", publicId);
        BookingResponseDTO response = bookingService.convertLeadToBooking(publicId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Lead converted to booking successfully", response, 201));
    }
}