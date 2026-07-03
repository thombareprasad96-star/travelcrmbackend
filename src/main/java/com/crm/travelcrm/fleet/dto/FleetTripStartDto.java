package com.crm.travelcrm.fleet.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** PATCH /api/fleet/trips/{publicId}/start body. Actual start defaults to now when omitted. */
@Getter
@Setter
public class FleetTripStartDto {

    @NotNull
    @PositiveOrZero
    private Integer startOdometer;

    private LocalDateTime startDatetime;
}