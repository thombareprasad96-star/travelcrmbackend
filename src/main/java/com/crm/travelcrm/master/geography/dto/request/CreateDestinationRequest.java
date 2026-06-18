package com.crm.travelcrm.master.geography.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Body for POST /api/destinations (flat) or POST /api/countries/{countryId}/destinations.
 *
 * For the flat endpoint, countryId must be supplied in the body.
 * For the path-variable endpoint, countryId from the path takes precedence.
 *
 * Fields NOT included (set by service layer):
 *  - tenantId  → derived from JWT principal
 *  - global    → assigned by service based on PLATFORM_ADMIN role
 */
@Data
public class CreateDestinationRequest {

    /** Required only when using POST /api/destinations (flat endpoint) — provide either countryId OR country name. */
    private Long countryId;

    /** Country name (string) — alternative to countryId; the service will look up the country by name. */
    private String country;

    @NotBlank(message = "Destination name is required")
    @Size(max = 150, message = "Name must not exceed 150 characters")
    private String name;

    private String description;

    @Size(max = 50, message = "Type must not exceed 50 characters")
    private String type;                        // "Domestic" | "International"

    @Size(max = 500, message = "Image path must not exceed 500 characters")
    private String imagePath;                   // Cloudinary secure_url

    @DecimalMin(value = "0.0", inclusive = true, message = "Price cannot be negative")
    private BigDecimal price;

    private String inclusions;                  // HTML from RichTextEditor
    private String exclusions;
    private String paymentPolicies;
    private String cancellationPolicies;
    private String bookingTerms;

    @Size(max = 20)
    private String status;
}