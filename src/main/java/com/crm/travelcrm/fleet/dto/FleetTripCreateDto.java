package com.crm.travelcrm.fleet.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * POST /api/fleet/trips body. Status is derived, never sent: an {@code endDatetime}
 * makes it a post-facto COMPLETED diary entry; otherwise the trip is created PLANNED
 * and started via PATCH /{publicId}/start.
 */
@Getter
@Setter
public class FleetTripCreateDto {

    @NotNull
    private UUID vehiclePublicId;

    @NotNull
    private UUID driverPublicId;

    /** Optional link to a booking — resolved + tenant-validated server-side. */
    private UUID bookingPublicId;

    @NotNull
    private LocalDateTime startDatetime;

    private LocalDateTime endDatetime;

    @PositiveOrZero
    private Integer startOdometer;

    @PositiveOrZero
    private Integer endOdometer;

    @Size(max = 150)
    private String routeFrom;

    @Size(max = 150)
    private String routeTo;

    @Size(max = 150)
    private String purpose;

    @PositiveOrZero
    private BigDecimal fuelCost;

    @PositiveOrZero
    private BigDecimal tollCost;

    @PositiveOrZero
    private BigDecimal driverAllowance;

    private String remarks;
}