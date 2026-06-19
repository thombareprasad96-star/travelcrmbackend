package com.crm.travelcrm.master.geography.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Body for creating a city.
 *
 * <p>Parent resolution:</p>
 * <ul>
 *   <li><b>Country (required)</b> — supply {@link #countryId} (preferred) or
 *       {@link #country} (name). On the nested route
 *       {@code POST /api/v1/destinations/{destinationId}/cities} the country is
 *       derived from the destination instead.</li>
 *   <li><b>Destination (optional)</b> — supply {@link #destinationId} to link the
 *       city to a destination. Its country must match the resolved country.</li>
 * </ul>
 */
@Data
public class CreateCityRequest {

    @NotBlank(message = "City name is required")
    @Size(max = 120, message = "Name must not exceed 120 characters")
    private String name;

    /** Country id — preferred way to set the city's required parent country. */
    private Long countryId;

    /** Country name — alternative to {@link #countryId} (resolved by name). */
    @Size(max = 120)
    private String country;

    /** Optional destination link. Its country must equal the resolved country. */
    private Long destinationId;

    /** Airport/city code (e.g. BOM). */
    @Size(max = 10)
    private String code;

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