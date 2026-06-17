package com.crm.travelcrm.master.geography.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Body for {@code PUT /api/v1/countries/{countryId}} — partial update. All fields
 * are nullable; only non-null values are applied by the mapper.
 */
@Data
public class UpdateCountryRequest {

    @Size(max = 120, message = "Name must not exceed 120 characters")
    private String name;

    @Size(min = 2, max = 2, message = "Code must be a 2-character ISO code")
    private String code;

    private String description;

    @Size(max = 500, message = "Flag must not exceed 500 characters")
    private String flag;

    @Size(max = 64, message = "Timezone must not exceed 64 characters")
    private String timezone;
}