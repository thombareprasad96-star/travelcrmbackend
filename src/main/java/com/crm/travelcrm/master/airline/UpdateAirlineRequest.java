package com.crm.travelcrm.master.airline;

import lombok.Data;

@Data
public class UpdateAirlineRequest {

    private Long cityId;
    private String name;
    private String status;
    private String logo;
    private String country;
    private String fleet;
    private String iata;
}