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
    boolean active;
    UUID platformSourcePublicId;

    /**
     * True when the marketplace catalog owns this row's name and description. {@code price} stays
     * editable regardless — it is the tenant's selling price and no sync ever overwrites it. So the
     * master screen should lock the two text fields on a platform-owned plan, not the whole row.
     */
    boolean platformOwned;
}
