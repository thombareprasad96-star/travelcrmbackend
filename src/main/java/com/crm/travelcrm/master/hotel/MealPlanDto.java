package com.crm.travelcrm.master.hotel;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.UUID;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MealPlanDto {

    Long mealPlanId;
    UUID publicId;
    Long hotelId;
    String name;
    String description;
    BigDecimal price;
}