package com.crm.travelcrm.booking.controller;

import com.crm.travelcrm.booking.dto.response.BookingAssigneeDto;
import com.crm.travelcrm.booking.service.BookingService;
import com.crm.travelcrm.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only source for the booking "Assigned To" dropdown, used by both the New Booking form and
 * the Convert-to-Booking modal. Gated on {@code BOOKING_CREATE} — only someone who can create a
 * booking needs the pool. Nothing is persisted here; the authoritative decision (and the validation
 * of whatever the client picks) happens on the create/convert call itself.
 */
@RestController
@RequestMapping("/api/bookings/assignment")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('BOOKING_CREATE')")
public class BookingAssignmentController {

    private final BookingService bookingService;

    @GetMapping("/eligible-users")
    public ResponseEntity<ApiResponse<List<BookingAssigneeDto>>> eligibleUsers() {
        return ResponseEntity.ok(ApiResponse.success(
                "Eligible assignees fetched", bookingService.eligibleAssignees()));
    }
}
