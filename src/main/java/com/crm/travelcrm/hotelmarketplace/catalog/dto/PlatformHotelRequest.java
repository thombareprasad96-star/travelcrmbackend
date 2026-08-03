package com.crm.travelcrm.hotelmarketplace.catalog.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * SuperAdmin create/update payload for a catalog hotel. One DTO for both verbs — the field set is
 * identical and a second class would only drift.
 *
 * <p>{@code status} is deliberately absent: publishing is its own audited endpoint, not something a
 * general edit can do by including one more field.</p>
 */
@Data
public class PlatformHotelRequest {

    @NotBlank
    @Size(max = 200)
    private String name;

    /** ISO country code. The sync matches a tenant Country on this, so it must be the code. */
    @Size(max = 3)
    private String countryCode;

    @Size(max = 120)
    private String stateName;

    @NotBlank
    @Size(max = 120)
    private String cityName;

    @Size(max = 20)
    private String cityCode;

    @Size(max = 500)
    private String address;

    private Double latitude;
    private Double longitude;

    @Min(1) @Max(7)
    private Integer stars;

    @Min(0) @Max(5)
    private Double rating;

    @Size(max = 500)
    private String website;

    @Size(max = 500)
    private String mapUrl;

    private String overview;

    @Size(max = 500)
    private String primaryImageUrl;

    /** Guest-facing contact for the voucher — never a settlement contact. */
    @Size(max = 50)
    private String phone;

    @Size(max = 100)
    private String email;

    private List<String> amenities;

    private UUID supplierVendorPublicId;
}
