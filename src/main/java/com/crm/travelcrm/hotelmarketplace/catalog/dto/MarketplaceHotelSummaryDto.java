package com.crm.travelcrm.hotelmarketplace.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * One row of a tenant's catalog search.
 *
 * <p>Carries no {@code status} (everything returned is ACTIVE by construction), no supplier link and
 * no {@code catalogVersion} — a tenant has no use for the platform's internal bookkeeping and every
 * field omitted here is a field that cannot leak.</p>
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MarketplaceHotelSummaryDto {

    UUID publicId;
    String name;
    String cityName;
    String stateName;
    String countryCode;
    Integer stars;
    Double rating;
    String primaryImageUrl;
    Integer roomCount;

    /**
     * Whether THIS tenant already holds a projection of this hotel. Lets the UI show "Imported"
     * instead of offering a second import that the unique index would reject anyway.
     */
    boolean alreadyImported;

    /** The tenant's own Hotel Master row, when {@link #alreadyImported} is true. */
    UUID tenantHotelPublicId;
}
