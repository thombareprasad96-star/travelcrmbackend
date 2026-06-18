package com.crm.travelcrm.master.hotel;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateMealPlanRequest {

    @NotBlank
    private String name;

    private String description;
    private BigDecimal price;
}