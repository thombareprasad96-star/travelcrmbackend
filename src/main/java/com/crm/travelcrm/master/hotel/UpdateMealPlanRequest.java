package com.crm.travelcrm.master.hotel;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateMealPlanRequest {

    private String name;
    private String description;
    private BigDecimal price;
}