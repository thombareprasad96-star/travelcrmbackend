package com.crm.travelcrm.master.hotel.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MealPlanRequestDTO {

    @NotBlank(message = "Meal plan name is required")
    private String name;

    @NotNull(message = "Price is required")
    private Double price;

    private String description;
}