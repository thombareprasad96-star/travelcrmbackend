package com.crm.travelcrm.hotelmarketplace.sync;

import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.hotelmarketplace.catalog.dto.HotelImportResultDto;
import com.crm.travelcrm.hotelmarketplace.catalog.entity.PlatformHotel;
import com.crm.travelcrm.hotelmarketplace.catalog.entity.PlatformHotelMealPlan;
import com.crm.travelcrm.hotelmarketplace.catalog.entity.PlatformHotelRoom;
import com.crm.travelcrm.hotelmarketplace.catalog.repository.PlatformHotelMealPlanRepository;
import com.crm.travelcrm.hotelmarketplace.catalog.repository.PlatformHotelRepository;
import com.crm.travelcrm.hotelmarketplace.catalog.repository.PlatformHotelRoomRepository;
import com.crm.travelcrm.master.geography.entity.City;
import com.crm.travelcrm.master.hotel.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Creates and refreshes a tenant's local Hotel Master projection of a catalog hotel.
 *
 * <p>The projection exists so every screen that already reads the Hotel Master — quotations, the
 * hotel dropdown, the itinerary builder — keeps working unchanged for a marketplace hotel. It is a
 * one-way copy: platform-owned descriptive fields flow down, and nothing ever flows back up.</p>
 *
 * <h3>Three rules this class exists to enforce</h3>
 * <ul>
 *   <li><b>Tenant-local fields survive.</b> {@code isDefault}, the tenant's own contact person, and
 *       above all {@code MealPlan.price} — the tenant's SELLING price — are never overwritten. The
 *       catalog has no price to copy, and clobbering theirs with a blank would quietly zero what
 *       they charge.</li>
 *   <li><b>Children are upserted, never recreated.</b> Matching on {@code platformSourcePublicId}
 *       means a re-sync updates the row it wrote last time. Deleting and re-adding would mint a new
 *       {@code publicId} on every sync and orphan any quotation line naming the old one.</li>
 *   <li><b>A vanished child is deactivated, not deleted.</b> It still has to resolve for the
 *       history that references it.</li>
 * </ul>
 *
 * <p>Idempotent by {@code catalogVersion}: replaying the same version is a no-op, which is what
 * makes the whole thing safe to retry.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotelMasterProjectionService {

    private final HotelRepository hotelRepository;
    private final PlatformHotelRepository platformHotelRepository;
    private final PlatformHotelRoomRepository platformRoomRepository;
    private final PlatformHotelMealPlanRepository platformMealPlanRepository;
    private final HotelGeoResolver geoResolver;

    /** This tenant's projection of a catalog hotel, if it has one. */
    @Transactional(readOnly = true)
    public Optional<Hotel> findProjection(UUID platformHotelPublicId, Long tenantId) {
        return hotelRepository.findByTenantIdAndPlatformHotelPublicIdAndDeletedAtIsNull(
                tenantId, platformHotelPublicId);
    }

    /**
     * Import the catalog hotel into this tenant's master, or refresh the projection it already has.
     *
     * <p>A FIRST import with unresolvable geography is refused outright rather than half-created:
     * {@code Hotel.city} is a NOT NULL foreign key, so there is no such thing as a projection
     * without a city, and inventing one would put the hotel in the wrong place everywhere.</p>
     */
    @Transactional
    public HotelImportResultDto importOrSync(PlatformHotel source, Long tenantId) {
        Optional<Hotel> existing = hotelRepository
                .findByTenantIdAndPlatformHotelPublicIdAndDeletedAtIsNull(tenantId, source.getPublicId());

        if (existing.isPresent()) {
            return sync(existing.get(), source, tenantId, false);
        }

        City city = geoResolver.resolve(source, tenantId).orElseThrow(() -> new BusinessException(
                geoResolver.describeMissingGeography(source, tenantId), HttpStatus.CONFLICT));

        Hotel projection = new Hotel();
        projection.setTenantId(tenantId);
        projection.setCity(city);
        projection.setOrigin(HotelOrigin.PLATFORM_SYNC);
        projection.setPlatformHotelPublicId(source.getPublicId());
        return sync(projection, source, tenantId, true);
    }

    /** Refresh only when behind. Cheap enough to call on every read of a projected hotel. */
    @Transactional
    public void syncIfStale(Hotel projection, PlatformHotel source, Long tenantId) {
        if (!projection.isPlatformOwned()) {
            return;
        }
        Long have = projection.getPlatformCatalogVersion();
        if (have != null && have.equals(source.getCatalogVersion())) {
            return;   // idempotent: same version, nothing to do
        }
        sync(projection, source, tenantId, false);
    }

    /**
     * One unit of work for the reconciliation sweep: refresh this tenant's projection of one catalog
     * hotel.
     *
     * <p>Both sides are re-read HERE rather than handed over by the caller. The sweep runs on a
     * scheduler thread with no transaction, so anything it loaded would arrive detached and the first
     * touch of {@code projection.getRoomTypes()} would blow up on a closed session.</p>
     *
     * <p>Enter {@code TenantScope} and call this CROSS-BEAN — this is the transaction the tenant
     * filter latches onto.</p>
     */
    @Transactional
    public void resyncStale(UUID platformHotelPublicId, Long tenantId) {
        Hotel projection = hotelRepository
                .findByTenantIdAndPlatformHotelPublicIdAndDeletedAtIsNull(tenantId, platformHotelPublicId)
                .orElse(null);
        if (projection == null) {
            return;   // trashed between the sweep's read and now
        }

        PlatformHotel source = platformHotelRepository
                .findByPublicIdAndDeletedAtIsNull(platformHotelPublicId).orElse(null);
        if (source == null) {
            // The catalog delete path refuses while any projection exists, so reaching here means the
            // source went away out of band. Park the row as SOURCE_INACTIVE: it is certainly not
            // bookable, and leaving it STALE would keep it in the sweep's queue, unfixable, forever.
            log.warn("Projection {} (tenant {}) points at catalog hotel {}, which no longer exists — "
                            + "marking SOURCE_INACTIVE", projection.getPublicId(), tenantId, platformHotelPublicId);
            projection.setMarketplaceBookable(false);
            projection.setSyncStatus(HotelSyncStatus.SOURCE_INACTIVE);
            hotelRepository.save(projection);
            return;
        }

        syncIfStale(projection, source, tenantId);
    }

    // ── the copy ────────────────────────────────────────────────────────────

    private HotelImportResultDto sync(Hotel projection, PlatformHotel source, Long tenantId, boolean created) {
        // Location may have moved in the catalog. Re-resolve, but NEVER move the hotel to a city we
        // had to guess at — an unresolvable change leaves it where it is and raises a visible flag.
        HotelSyncStatus status = HotelSyncStatus.SYNCED;
        String message = null;
        if (!created) {
            Optional<City> resolved = geoResolver.resolve(source, tenantId);
            if (resolved.isPresent()) {
                projection.setCity(resolved.get());
            } else {
                status = HotelSyncStatus.LOCATION_MAPPING_REQUIRED;
                message = geoResolver.describeMissingGeography(source, tenantId);
            }
        }

        // ── Platform-owned descriptive fields (design doc §6.2) ──
        projection.setName(source.getName());
        projection.setStars(source.getStars());
        projection.setRating(source.getRating());
        projection.setAddress(source.getAddress());
        projection.setWebsite(source.getWebsite());
        projection.setMapUrl(source.getMapUrl());
        projection.setLatitude(source.getLatitude());
        projection.setLongitude(source.getLongitude());
        projection.setOverview(source.getOverview());
        projection.setImagePath(source.getPrimaryImageUrl());
        projection.setPhone(source.getPhone());
        projection.setEmail(source.getEmail());

        projection.getAmenities().clear();
        projection.getAmenities().addAll(new ArrayList<>(source.getAmenities()));

        // NOT copied, on purpose: isDefault and contactPerson are the tenant's own (§6.4), and there
        // is nothing on the catalog side to copy from anyway.

        syncRooms(projection, platformRoomRepository
                .findByHotelIdAndDeletedAtIsNullOrderByNameAsc(source.getId()));
        syncMealPlans(projection, platformMealPlanRepository
                .findByHotelIdAndDeletedAtIsNullOrderByCodeAsc(source.getId()));

        boolean sellable = source.getStatus().isSellable();
        projection.setMarketplaceBookable(sellable);
        if (!sellable) {
            status = HotelSyncStatus.SOURCE_INACTIVE;
        }
        projection.setSyncStatus(status);
        projection.setPlatformCatalogVersion(source.getCatalogVersion());
        projection.setLastSyncedAt(LocalDateTime.now());

        Hotel saved = hotelRepository.save(projection);
        log.info("Hotel projection {} for tenant {} → {} (catalog v{})",
                created ? "created" : "synced", tenantId, saved.getPublicId(), source.getCatalogVersion());

        return HotelImportResultDto.builder()
                .tenantHotelPublicId(saved.getPublicId())
                .platformHotelPublicId(source.getPublicId())
                .name(saved.getName())
                .created(created)
                .syncStatus(status)
                .platformCatalogVersion(source.getCatalogVersion())
                .message(message)
                .build();
    }

    private void syncRooms(Hotel projection, List<PlatformHotelRoom> sourceRooms) {
        List<RoomType> local = projection.getRoomTypes();
        Set<UUID> seen = new HashSet<>();

        for (PlatformHotelRoom src : sourceRooms) {
            seen.add(src.getPublicId());
            RoomType target = local.stream()
                    .filter(rt -> src.getPublicId().equals(rt.getPlatformSourcePublicId()))
                    .findFirst()
                    .orElseGet(() -> {
                        RoomType created = new RoomType();
                        created.setHotel(projection);
                        created.setPlatformSourcePublicId(src.getPublicId());
                        local.add(created);
                        return created;
                    });
            target.setName(src.getName());
            target.setSize(src.getSize());
            // The tenant master models one occupancy figure; the catalog's max is the meaningful one.
            target.setOccupancy(src.getMaxOccupancy());
            target.setBedType(src.getBedType());
            target.setDescription(src.getDescription());
            target.setActive(src.isActive());
            target.getImages().clear();
            target.getImages().addAll(new ArrayList<>(src.getImages()));
        }

        deactivateVanished(local, seen, RoomType::getPlatformSourcePublicId, RoomType::setActive);
    }

    private void syncMealPlans(Hotel projection, List<PlatformHotelMealPlan> sourcePlans) {
        List<MealPlan> local = projection.getMealPlans();
        Set<UUID> seen = new HashSet<>();

        for (PlatformHotelMealPlan src : sourcePlans) {
            seen.add(src.getPublicId());
            MealPlan target = local.stream()
                    .filter(mp -> src.getPublicId().equals(mp.getPlatformSourcePublicId()))
                    .findFirst()
                    .orElseGet(() -> {
                        MealPlan created = new MealPlan();
                        created.setHotel(projection);
                        created.setPlatformSourcePublicId(src.getPublicId());
                        local.add(created);
                        return created;
                    });
            target.setName(src.getName());
            target.setDescription(src.getDescription());
            target.setActive(src.isActive());
            // price is deliberately untouched — it is the TENANT's selling price. The catalog has no
            // price to copy, so writing anything here would overwrite their number with a blank.
        }

        deactivateVanished(local, seen, MealPlan::getPlatformSourcePublicId, MealPlan::setActive);
    }

    /**
     * A child that disappeared from the catalog is deactivated, never removed: quotations and
     * bookings still name it, and {@code orphanRemoval = true} on the parent collection means a
     * removal here would be a hard DELETE.
     */
    private <T> void deactivateVanished(List<T> local, Set<UUID> stillPresent,
                                        java.util.function.Function<T, UUID> sourceIdOf,
                                        java.util.function.BiConsumer<T, Boolean> setActive) {
        for (T row : local) {
            UUID sourceId = sourceIdOf.apply(row);
            if (sourceId != null && !stillPresent.contains(sourceId)) {
                setActive.accept(row, false);
            }
        }
    }
}
