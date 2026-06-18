package com.crm.travelcrm.master.airline;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateAirlineRequest {

    private Long cityId;

    @NotBlank
    private String name;

    private String status;
    private String logo;
    private String country;
    private String fleet;
    private String iata;
}