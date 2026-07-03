package com.crm.travelcrm.fleet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** POST /api/fleet/vehicles/{vehiclePublicId}/maintenance-logs and PUT /api/fleet/maintenance-logs/{publicId} body. */
@Getter
@Setter
public class FleetMaintenanceLogRequestDto {

    @NotNull
    private LocalDate serviceDate;

    @NotBlank
    @Size(max = 100)
    private String serviceType;

    @PositiveOrZero
    private BigDecimal cost;

    /** Garage/workshop name — free text, not a Vendor link. */
    @Size(max = 150)
    private String vendorName;

    @PositiveOrZero
    private Integer odometer;

    private LocalDate nextServiceDueDate;

    @PositiveOrZero
    private Integer nextServiceDueKm;

    private String notes;
}