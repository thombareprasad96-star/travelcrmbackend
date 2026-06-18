package com.crm.travelcrm.master.addon;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateAddonRequest {

    private Long cityId;
    private String name;
    private String description;
    private BigDecimal price;
    private Boolean active;
}