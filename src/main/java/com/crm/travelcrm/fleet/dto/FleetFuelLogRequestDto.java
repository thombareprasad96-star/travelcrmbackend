package com.crm.travelcrm.fleet.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** POST /api/fleet/vehicles/{vehiclePublicId}/fuel-logs and PUT /api/fleet/fuel-logs/{publicId} body. */
@Getter
@Setter
public class FleetFuelLogRequestDto {

    @NotNull
    private LocalDate date;

    @NotNull
    @Positive
    private BigDecimal liters;

    @NotNull
    @PositiveOrZero
    private BigDecimal cost;

    @PositiveOrZero
    private Integer odometer;

    private String notes;
}