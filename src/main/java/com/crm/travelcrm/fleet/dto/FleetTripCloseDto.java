package com.crm.travelcrm.fleet.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** PATCH /api/fleet/trips/{publicId}/close body. Actual end defaults to now when omitted. */
@Getter
@Setter
public class FleetTripCloseDto {

    @NotNull
    @PositiveOrZero
    private Integer endOdometer;

    private LocalDateTime endDatetime;

    @PositiveOrZero
    private BigDecimal fuelCost;

    @PositiveOrZero
    private BigDecimal tollCost;

    @PositiveOrZero
    private BigDecimal driverAllowance;

    private String remarks;
}