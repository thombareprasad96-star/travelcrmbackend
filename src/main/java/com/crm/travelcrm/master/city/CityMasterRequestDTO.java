package com.crm.travelcrm.master.city;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CityMasterRequestDTO {

    @NotBlank(message = "Country is required")
    private String country;

    @NotBlank(message = "City name is required")
    private String name;

    private String code;

    private String status;
}