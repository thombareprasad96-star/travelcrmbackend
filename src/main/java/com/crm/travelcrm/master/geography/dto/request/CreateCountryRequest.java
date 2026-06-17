package com.crm.travelcrm.master.geography.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Body for {@code POST /api/v1/countries}. */
@Data
public class CreateCountryRequest {

    @NotBlank(message = "Country name is required")
    @Size(max = 120, message = "Name must not exceed 120 characters")
    private String name;

    @NotBlank(message = "Country code is required")
    @Size(min = 2, max = 2, message = "Code must be a 2-character ISO code")
    private String code;

    private String description;

    @Size(max = 500, message = "Flag must not exceed 500 characters")
    private String flag;

    @Size(max = 64, message = "Timezone must not exceed 64 characters")
    private String timezone;
}