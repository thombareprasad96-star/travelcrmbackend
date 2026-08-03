package com.crm.travelcrm.hotelmarketplace.catalog.mapper;

import com.crm.travelcrm.hotelmarketplace.catalog.dto.*;
import com.crm.travelcrm.hotelmarketplace.catalog.entity.PlatformHotel;
import com.crm.travelcrm.hotelmarketplace.catalog.entity.PlatformHotelMealPlan;
import com.crm.travelcrm.hotelmarketplace.catalog.entity.PlatformHotelRoom;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Entity → DTO for both catalog audiences.
 *
 * <p><b>Hand-written, not MapStruct, and that is the point.</b> The same entity feeds a SuperAdmin
 * DTO that shows everything and a tenant DTO that must never carry the supplier link or the catalog
 * version. A generated mapper's job is to copy every field it can match — exactly the behaviour that
 * turns "add a column" into a quiet leak. This is the same whitelist discipline the traveler portal
 * uses for the same reason.</p>
 *
 * <p>Nothing here loads a collection that the caller did not already fetch: {@code rooms},
 * {@code mealPlans} and {@code amenities} are LAZY, so the summary mappers touch none of them.</p>
 */
@Component
public class PlatformHotelMapper {

    // ── SuperAdmin ──────────────────────────────────────────────────────────

    /** Full admin view. Pass {@code linkedTenantCount} null on list rows where it is not needed. */
    public PlatformHotelAdminDto toAdminDto(PlatformHotel h,
                                            List<PlatformHotelRoom> rooms,
                                            List<PlatformHotelMealPlan> mealPlans,
                                            Long linkedTenantCount) {
        return PlatformHotelAdminDto.builder()
                .publicId(h.getPublicId())
                .name(h.getName())
                .status(h.getStatus())
                .countryCode(h.getCountryCode())
                .stateName(h.getStateName())
                .cityName(h.getCityName())
                .cityCode(h.getCityCode())
                .address(h.getAddress())
                .latitude(h.getLatitude())
                .longitude(h.getLongitude())
                .stars(h.getStars())
                .rating(h.getRating())
                .website(h.getWebsite())
                .mapUrl(h.getMapUrl())
                .overview(h.getOverview())
                .primaryImageUrl(h.getPrimaryImageUrl())
                .phone(h.getPhone())
                .email(h.getEmail())
                .amenities(copy(h.getAmenities()))
                .supplierVendorPublicId(h.getSupplierVendorPublicId())
                .confirmationMode(h.getConfirmationMode())
                .catalogVersion(h.getCatalogVersion())
                .rooms(rooms == null ? null : rooms.stream().map(this::toRoomDto).toList())
                .mealPlans(mealPlans == null ? null : mealPlans.stream().map(this::toMealPlanDto).toList())
                .linkedTenantCount(linkedTenantCount)
                .createdAt(h.getCreatedAt())
                .updatedAt(h.getUpdatedAt())
                .build();
    }

    /** List row — no children, no linked count. */
    public PlatformHotelAdminDto toAdminSummary(PlatformHotel h) {
        return toAdminDto(h, null, null, null);
    }

    // ── Tenant ──────────────────────────────────────────────────────────────

    public MarketplaceHotelSummaryDto toMarketplaceSummary(PlatformHotel h,
                                                           Integer roomCount,
                                                           UUID tenantHotelPublicId) {
        return MarketplaceHotelSummaryDto.builder()
                .publicId(h.getPublicId())
                .name(h.getName())
                .cityName(h.getCityName())
                .stateName(h.getStateName())
                .countryCode(h.getCountryCode())
                .stars(h.getStars())
                .rating(h.getRating())
                .primaryImageUrl(h.getPrimaryImageUrl())
                .roomCount(roomCount)
                .alreadyImported(tenantHotelPublicId != null)
                .tenantHotelPublicId(tenantHotelPublicId)
                .build();
    }

    /**
     * Tenant detail. Only ACTIVE children are offered — an inactive room still resolves for a
     * historical booking, but it must not appear as something new to book.
     */
    public MarketplaceHotelDetailDto toMarketplaceDetail(PlatformHotel h,
                                                         List<PlatformHotelRoom> activeRooms,
                                                         List<PlatformHotelMealPlan> activeMealPlans,
                                                         UUID tenantHotelPublicId) {
        return MarketplaceHotelDetailDto.builder()
                .publicId(h.getPublicId())
                .name(h.getName())
                .cityName(h.getCityName())
                .stateName(h.getStateName())
                .countryCode(h.getCountryCode())
                .address(h.getAddress())
                .latitude(h.getLatitude())
                .longitude(h.getLongitude())
                .stars(h.getStars())
                .rating(h.getRating())
                .website(h.getWebsite())
                .mapUrl(h.getMapUrl())
                .overview(h.getOverview())
                .primaryImageUrl(h.getPrimaryImageUrl())
                .phone(h.getPhone())
                .email(h.getEmail())
                .amenities(copy(h.getAmenities()))
                .rooms(activeRooms.stream().map(this::toRoomDto).toList())
                .mealPlans(activeMealPlans.stream().map(this::toMealPlanDto).toList())
                .alreadyImported(tenantHotelPublicId != null)
                .tenantHotelPublicId(tenantHotelPublicId)
                .build();
    }

    // ── Children (audience-neutral: neither carries commercial data) ────────

    public PlatformRoomDto toRoomDto(PlatformHotelRoom r) {
        return PlatformRoomDto.builder()
                .publicId(r.getPublicId())
                .name(r.getName())
                .maxAdults(r.getMaxAdults())
                .maxChildren(r.getMaxChildren())
                .maxOccupancy(r.getMaxOccupancy())
                .bedType(r.getBedType())
                .size(r.getSize())
                .description(r.getDescription())
                .active(r.isActive())
                .images(copy(r.getImages()))
                .build();
    }

    public PlatformMealPlanDto toMealPlanDto(PlatformHotelMealPlan m) {
        return PlatformMealPlanDto.builder()
                .publicId(m.getPublicId())
                .code(m.getCode())
                .name(m.getName())
                .description(m.getDescription())
                .active(m.isActive())
                .build();
    }

    /** Detach the Hibernate collection — a PersistentBag on a DTO serialises outside the session. */
    private static List<String> copy(List<String> src) {
        return src == null ? null : new ArrayList<>(src);
    }
}
