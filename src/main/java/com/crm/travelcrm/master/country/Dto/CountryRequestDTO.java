package com.crm.travelcrm.master.country.Dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CountryRequestDTO {

    @NotBlank(message = "Country name is required")
    private String countryName;

    @NotBlank(message = "Country code is required")
    private String countryCode;
}