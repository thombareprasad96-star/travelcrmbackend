package com.crm.travelcrm.hotelmarketplace.catalog.dto;

import com.crm.travelcrm.hotelmarketplace.catalog.enums.MealPlanCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/** A catalog meal plan. Identical for both audiences — a meal plan carries no price. */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PlatformMealPlanDto {

    UUID publicId;
    MealPlanCode code;
    String name;
    String description;
    boolean active;
}
