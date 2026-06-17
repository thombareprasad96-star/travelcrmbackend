package com.crm.travelcrm.master.geography.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Body for {@code POST /api/v1/destinations/{destinationId}/cities}.
 * The parent destination comes from the path, not the body.
 */
@Data
public class CreateCityRequest {

    @NotBlank(message = "City name is required")
    @Size(max = 120, message = "Name must not exceed 120 characters")
    private String name;

    @Size(max = 120, message = "State must not exceed 120 characters")
    private String state;

    @DecimalMin(value = "-90.0",  message = "Latitude must be between -90 and 90")
    @DecimalMax(value = "90.0",   message = "Latitude must be between -90 and 90")
    private Double latitude;

    @DecimalMin(value = "-180.0", message = "Longitude must be between -180 and 180")
    @DecimalMax(value = "180.0",  message = "Longitude must be between -180 and 180")
    private Double longitude;

    @Size(max = 500, message = "Image URL must not exceed 500 characters")
    private String imageUrl;

    @Positive(message = "Days to stay must be greater than 0")
    private Integer daysToStay;
}