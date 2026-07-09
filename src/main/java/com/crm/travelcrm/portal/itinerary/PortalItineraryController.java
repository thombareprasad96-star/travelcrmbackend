package com.crm.travelcrm.portal.itinerary;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.portal.itinerary.dto.TravelerItineraryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Traveler day-wise itinerary for one of their own bookings. Ownership is enforced in the service
 * (foreign publicId → 404). Additive: a distinct controller on the {@code /api/portal/bookings} base
 * (only the {@code /{id}/itinerary} sub-path is new — no clash with the existing booking endpoints).
 */
@RestController
@RequestMapping("/api/portal/bookings")
@RequiredArgsConstructor
public class PortalItineraryController {

    private final PortalItineraryService service;

    @GetMapping("/{publicId}/itinerary")
    public ResponseEntity<ApiResponse<TravelerItineraryDto>> itinerary(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success("Itinerary fetched", service.getItinerary(publicId)));
    }
}