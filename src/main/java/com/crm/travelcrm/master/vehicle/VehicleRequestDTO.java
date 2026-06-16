package com.crm.travelcrm.master.vehicle;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VehicleRequestDTO {

    @NotBlank(message = "Vehicle name is required")
    private String name;

    @NotBlank(message = "Vehicle type is required")
    private String type;

    @Min(value = 1, message = "Capacity must be greater than 0")
    private Integer capacity;

    private String description;

    private String imagePath;
}