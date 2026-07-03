package com.crm.travelcrm.fleet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** POST / PUT /api/fleet/drivers body. PUT is a full replace of the form-owned fields — omitted/null optional fields are cleared. */
@Getter
@Setter
public class FleetDriverRequestDto {

    @NotBlank
    @Size(max = 150)
    private String name;

    @Size(max = 30)
    private String phone;

    @Size(max = 40)
    private String licenseNumber;

    private LocalDate licenseExpiry;

    private String notes;
}