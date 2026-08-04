package com.crm.travelcrm.hotelmarketplace.catalog.service;

import com.crm.travelcrm.hotelmarketplace.catalog.dto.*;
import com.crm.travelcrm.hotelmarketplace.catalog.enums.PlatformHotelStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * SuperAdmin management of the global hotel catalog.
 *
 * <p>Every method here runs in the platform realm with no {@code TenantContext}. Nothing is
 * tenant-scoped and nothing should be — but that also means no ambient filter protects these reads,
 * so this service must never be called from a tenant-facing path. Tenants go through
 * {@link MarketplaceCatalogService}, which only ever sees ACTIVE rows.
 */
public interface PlatformHotelCatalogService {

    Page<PlatformHotelAdminDto> search(PlatformHotelStatus status, String q, Pageable pageable);

    PlatformHotelAdminDto get(UUID publicId);

    PlatformHotelAdminDto create(PlatformHotelRequest request);

    PlatformHotelAdminDto update(UUID publicId, PlatformHotelRequest request);

    /** DRAFT/INACTIVE/SUSPENDED → ACTIVE. Refuses a hotel with no sellable room. */
    PlatformHotelAdminDto publish(UUID publicId);

    /**
     * ACTIVE → INACTIVE or SUSPENDED. Removes the hotel from tenant search immediately and marks
     * every existing tenant projection {@code SOURCE_INACTIVE}; it never deletes one, because
     * quotations and bookings reference them.
     */
    PlatformHotelAdminDto unpublish(UUID publicId, PlatformHotelStatus target, String reason);

    void delete(UUID publicId);

    // ── Rooms ───────────────────────────────────────────────────────────────

    PlatformRoomDto addRoom(UUID hotelPublicId, PlatformRoomRequest request);

    PlatformRoomDto updateRoom(UUID hotelPublicId, UUID roomPublicId, PlatformRoomRequest request);

    void deleteRoom(UUID hotelPublicId, UUID roomPublicId);

    // ── Meal plans ──────────────────────────────────────────────────────────

    PlatformMealPlanDto addMealPlan(UUID hotelPublicId, PlatformMealPlanRequest request);

    PlatformMealPlanDto updateMealPlan(UUID hotelPublicId, UUID mealPlanPublicId,
                                       PlatformMealPlanRequest request);

    void deleteMealPlan(UUID hotelPublicId, UUID mealPlanPublicId);
}
