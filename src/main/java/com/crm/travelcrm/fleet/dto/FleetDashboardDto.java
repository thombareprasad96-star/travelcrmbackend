package com.crm.travelcrm.fleet.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** GET /api/fleet/dashboard payload — counts, live trips, expiring documents, service-due vehicles. */
@Getter
@Builder
public class FleetDashboardDto {

    private final VehicleCounts vehicles;
    private final DriverCounts drivers;
    private final List<FleetTripResponseDto> ongoingTrips;
    private final List<FleetExpiringDocumentDto> expiringDocuments;
    private final List<FleetServiceDueDto> serviceDue;

    public record VehicleCounts(long total, long available, long onTrip, long maintenance, long outOfService) {
    }

    public record DriverCounts(long total, long active, long onTrip) {
    }
}