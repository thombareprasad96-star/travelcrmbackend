package com.crm.travelcrm.hotelmarketplace.catalog.dto;

import com.crm.travelcrm.hotelmarketplace.catalog.enums.MealPlanCode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * SuperAdmin create/update payload for a catalog meal plan.
 *
 * <p>No price: a meal plan is an inclusion, not a rate. {@code name} may be left blank — the code's
 * own default label is used, which keeps "CP" from being displayed as an unexplained acronym.</p>
 */
@Data
public class PlatformMealPlanRequest {

    @NotNull
    private MealPlanCode code;

    @Size(max = 150)
    private String name;

    private String description;

    private Boolean active;
}
