package com.crm.travelcrm.master.cruise;

import lombok.Data;

@Data
public class UpdateCruiseRequest {

    private Long cityId;
    private String name;
    private String description;
    private String image;
}