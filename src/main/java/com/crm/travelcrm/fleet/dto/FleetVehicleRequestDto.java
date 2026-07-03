package com.crm.travelcrm.fleet.dto;

import com.crm.travelcrm.fleet.enums.FleetOwnerType;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/** POST / PUT /api/fleet/vehicles body. PUT is a full replace of the form-owned fields — omitted/null optional fields are cleared. */
@Getter
@Setter
public class FleetVehicleRequestDto {

    @NotBlank
    @Size(max = 30)
    private String vehicleNumber;

    @Size(max = 60)
    private String type;

    @Size(max = 60)
    private String make;

    @Size(max = 60)
    private String model;

    @Min(1950)
    @Max(2100)
    private Integer year;

    @Min(1)
    private Integer seatingCapacity;

    @NotNull
    private FleetOwnerType ownerType;

    /** Optional link to an existing vendor — resolved + tenant-validated server-side. */
    private UUID vendorPublicId;

    private LocalDate insuranceExpiry;
    private LocalDate rcExpiry;
    private LocalDate permitExpiry;
    private LocalDate pucExpiry;

    private String notes;
}