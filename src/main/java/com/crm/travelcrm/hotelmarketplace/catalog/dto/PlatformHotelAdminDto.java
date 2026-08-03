package com.crm.travelcrm.hotelmarketplace.catalog.dto;

import com.crm.travelcrm.hotelmarketplace.catalog.enums.ConfirmationMode;
import com.crm.travelcrm.hotelmarketplace.catalog.enums.PlatformHotelStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A catalog hotel as the SuperAdmin sees it — everything, including the fields a tenant must never
 * receive ({@code status} while DRAFT, {@code supplierVendorPublicId}, {@code catalogVersion}).
 *
 * <p>This is a separate class from {@link MarketplaceHotelDetailDto} on purpose. Hiding platform
 * internals with JSON annotations on a shared DTO is one careless {@code @JsonInclude} away from a
 * leak, and the compiler cannot help; two types means the tenant endpoint physically cannot return
 * a supplier link (design doc §11).</p>
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PlatformHotelAdminDto {

    UUID publicId;
    String name;
    PlatformHotelStatus status;

    String countryCode;
    String stateName;
    String cityName;
    String cityCode;
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

    UUID supplierVendorPublicId;
    ConfirmationMode confirmationMode;
    Long catalogVersion;

    List<PlatformRoomDto> rooms;
    List<PlatformMealPlanDto> mealPlans;

    /** How many tenants currently hold a projection of this hotel. Drives the unpublish warning. */
    Long linkedTenantCount;

    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
