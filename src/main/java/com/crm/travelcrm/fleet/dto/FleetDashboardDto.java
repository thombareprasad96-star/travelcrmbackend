package com.crm.travelcrm.fleet.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/** GET /api/fleet/dashboard payload — counts, live trips, expiring documents, service-due vehicles. */
@Getter
@Builder
public class FleetDashboardDto {

    private final VehicleCounts vehicles;
    private final DriverCounts drivers;
    private final MonthlySpend monthlySpend;
    private final TripStats tripsThisMonth;
    private final List<FleetTripResponseDto> ongoingTrips;
    private final List<FleetTripResponseDto> upcomingTrips;
    private final List<FleetExpiringDocumentDto> expiringDocuments;
    private final List<FleetServiceDueDto> serviceDue;

    public record VehicleCounts(long total, long available, long onTrip, long maintenance, long outOfService) {
    }

    public record DriverCounts(long total, long active, long onTrip) {
    }

    /** Current-calendar-month operating spend split by source (fuel + maintenance). */
    public record MonthlySpend(BigDecimal fuel, BigDecimal maintenance, BigDecimal total) {
    }

    /** Completed trips in the current calendar month + their total distance. */
    public record TripStats(long count, long distanceKm) {
    }
}