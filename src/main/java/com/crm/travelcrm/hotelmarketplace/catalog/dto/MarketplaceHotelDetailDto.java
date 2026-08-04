package com.crm.travelcrm.hotelmarketplace.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.UUID;

/**
 * A catalog hotel as a TENANT sees it: descriptive content and room/meal options, nothing else.
 *
 * <p>Deliberately missing, and each for a reason: {@code status} (only ACTIVE is ever returned),
 * {@code supplierVendorPublicId} (whose supplier it is, is not the tenant's business),
 * {@code catalogVersion} (platform bookkeeping), and any rate or availability field (they do not
 * exist yet, and when they do, the tenant sees a final payable — never a supplier net).</p>
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MarketplaceHotelDetailDto {

    UUID publicId;
    String name;
    String cityName;
    String stateName;
    String countryCode;
    String address;
    Double latitude;
    Double longitude;

    Integer stars;
    Double rating;
    String website;
    String mapUrl;
    String overview;
    String primaryImageUrl;
    String phone;
    String email;
    List<String> amenities;

    List<PlatformRoomDto> rooms;
    List<PlatformMealPlanDto> mealPlans;

    boolean alreadyImported;
    UUID tenantHotelPublicId;
}
