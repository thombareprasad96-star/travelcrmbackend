package com.crm.travelcrm.portal.booking;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.portal.booking.dto.TravelerBookingDetailDto;
import com.crm.travelcrm.portal.booking.dto.TravelerBookingSummaryDto;
import com.crm.travelcrm.portal.booking.dto.TravelerPaymentStatusDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Traveler "my trips" API. Authentication is enforced by the portal chain; object-level ownership
 * is enforced in the service. All responses are traveler-safe DTOs in the standard envelope.
 */
@RestController
@RequestMapping("/api/portal/bookings")
@RequiredArgsConstructor
public class PortalBookingController {

    private final PortalBookingService portalBookingService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<TravelerBookingSummaryDto>>> myBookings() {
        return ResponseEntity.ok(ApiResponse.success("Bookings fetched", portalBookingService.myBookings()));
    }

    @GetMapping("/{publicId}")
    public ResponseEntity<ApiResponse<TravelerBookingDetailDto>> getBooking(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success("Booking fetched", portalBookingService.getMyBooking(publicId)));
    }

    @GetMapping("/{publicId}/payment")
    public ResponseEntity<ApiResponse<TravelerPaymentStatusDto>> getPayment(@PathVariable UUID publicId) {
        return ResponseEntity.ok(
                ApiResponse.success("Payment status fetched", portalBookingService.getPaymentStatus(publicId)));
    }
}
