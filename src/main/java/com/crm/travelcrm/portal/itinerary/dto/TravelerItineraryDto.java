package com.crm.travelcrm.portal.itinerary.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * Traveler-safe day-wise itinerary, assembled from the booking's source Quotation. Deliberately omits
 * ALL pricing (hotel/vehicle/sightseeing amounts, discount, markup, tax), vendor data, staff names and
 * internal ids. {@code available=false} when the booking has no linked quotation (only the header is
 * filled from the booking itself).
 */
@Data
@Builder
public class TravelerItineraryDto {
    private boolean available;

    // Trip header
    private String title;
    private String destination;
    private LocalDate travelDate;
    private Integer adults;
    private Integer children;
    private Integer infants;

    // Sections
    private List<ItineraryHotelDto> hotels;
    private List<ItineraryVehicleDto> vehicles;
    private List<ItineraryDayDto> days;

    // Safe text lists
    private List<String> inclusions;
    private List<String> exclusions;
    private List<String> paymentPolicies;
    private List<String> cancellationPolicies;
}