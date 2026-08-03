package com.crm.travelcrm.hotelmarketplace.catalog.service;

import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.hotelmarketplace.catalog.dto.*;
import com.crm.travelcrm.hotelmarketplace.catalog.entity.PlatformHotel;
import com.crm.travelcrm.hotelmarketplace.catalog.entity.PlatformHotelMealPlan;
import com.crm.travelcrm.hotelmarketplace.catalog.entity.PlatformHotelRoom;
import com.crm.travelcrm.hotelmarketplace.catalog.enums.PlatformHotelStatus;
import com.crm.travelcrm.hotelmarketplace.catalog.mapper.PlatformHotelMapper;
import com.crm.travelcrm.hotelmarketplace.catalog.repository.PlatformHotelMealPlanRepository;
import com.crm.travelcrm.hotelmarketplace.catalog.repository.PlatformHotelRepository;
import com.crm.travelcrm.hotelmarketplace.catalog.repository.PlatformHotelRoomRepository;
import com.crm.travelcrm.master.hotel.Hotel;
import com.crm.travelcrm.master.hotel.HotelRepository;
import com.crm.travelcrm.master.hotel.HotelSyncStatus;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** @see PlatformHotelCatalogService */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformHotelCatalogServiceImpl implements PlatformHotelCatalogService {

    private static final String TARGET_TYPE = "PLATFORM_HOTEL";

    private final PlatformHotelRepository hotelRepository;
    private final PlatformHotelRoomRepository roomRepository;
    private final PlatformHotelMealPlanRepository mealPlanRepository;
    private final HotelRepository tenantHotelRepository;
    private final PlatformHotelMapper mapper;
    private final PlatformAuditRecorder auditRecorder;

    // ── Reads ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<PlatformHotelAdminDto> search(PlatformHotelStatus status, String q, Pageable pageable) {
        // Lower-cased and %-wrapped HERE: searchForAdmin compares the parameter straight against
        // LOWER(h.name), because a parameter wrapped in LOWER()/CONCAT() inside the query binds
        // untyped and fails with `lower(bytea) does not exist` whenever it is null.
        String needle = (q == null || q.isBlank()) ? null : "%" + q.trim().toLowerCase() + "%";
        return hotelRepository.searchForAdmin(status, needle, pageable).map(mapper::toAdminSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public PlatformHotelAdminDto get(UUID publicId) {
        PlatformHotel hotel = require(publicId);
        return mapper.toAdminDto(
                hotel,
                roomRepository.findByHotelIdAndDeletedAtIsNullOrderByNameAsc(hotel.getId()),
                mealPlanRepository.findByHotelIdAndDeletedAtIsNullOrderByCodeAsc(hotel.getId()),
                tenantHotelRepository.countProjectionsAcrossTenants(publicId));
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PlatformHotelAdminDto create(PlatformHotelRequest request) {
        PlatformHotel hotel = new PlatformHotel();
        apply(request, hotel);
        hotel.setStatus(PlatformHotelStatus.DRAFT);   // publishing is its own audited action
        hotel.setCatalogVersion(1L);

        PlatformHotel saved = hotelRepository.save(hotel);

        // Duplicate detection is advisory, never a block: two genuinely different properties can
        // share a name in one city, and refusing the second one would leave it un-onboardable.
        List<PlatformHotel> dupes = hotelRepository.findPossibleDuplicates(
                saved.getName(), saved.getCityName(), saved.getCountryCode());
        if (dupes.size() > 1) {
            log.warn("Possible duplicate catalog hotel '{}' in {} — {} rows share this name/city/country",
                    saved.getName(), saved.getCityName(), dupes.size());
        }

        audit(PlatformAuditAction.PLATFORM_HOTEL_CREATE, saved,
                "Created catalog hotel " + saved.getName() + " (" + saved.getCityName() + ")");
        return get(saved.getPublicId());
    }

    @Override
    @Transactional
    public PlatformHotelAdminDto update(UUID publicId, PlatformHotelRequest request) {
        PlatformHotel hotel = require(publicId);
        apply(request, hotel);
        // Every field this endpoint touches is one a tenant projection copies, so the version always
        // moves. Being conservative here is cheap; a missed bump leaves projections silently stale.
        hotel.bumpCatalogVersion();
        hotelRepository.save(hotel);
        markProjectionsStale(hotel, false);

        audit(PlatformAuditAction.PLATFORM_HOTEL_UPDATE, hotel,
                "Updated catalog hotel " + hotel.getName() + " → version " + hotel.getCatalogVersion());
        return get(publicId);
    }

    @Override
    @Transactional
    public PlatformHotelAdminDto publish(UUID publicId) {
        PlatformHotel hotel = require(publicId);

        // A hotel with nothing sellable would appear in search and dead-end there. Cheaper to refuse
        // than to explain to a tenant why a published hotel has no rooms.
        boolean hasSellableRoom = !roomRepository
                .findByHotelIdAndActiveTrueAndDeletedAtIsNullOrderByNameAsc(hotel.getId()).isEmpty();
        if (!hasSellableRoom) {
            throw new BusinessException(
                    "Add at least one active room before publishing " + hotel.getName() + ".",
                    HttpStatus.BAD_REQUEST);
        }

        hotel.setStatus(PlatformHotelStatus.ACTIVE);
        hotel.bumpCatalogVersion();
        hotelRepository.save(hotel);

        // Projections of a previously-unpublished hotel become bookable again.
        markProjectionsStale(hotel, true);

        audit(PlatformAuditAction.PLATFORM_HOTEL_PUBLISH, hotel, "Published " + hotel.getName());
        return get(publicId);
    }

    @Override
    @Transactional
    public PlatformHotelAdminDto unpublish(UUID publicId, PlatformHotelStatus target, String reason) {
        if (target != PlatformHotelStatus.INACTIVE && target != PlatformHotelStatus.SUSPENDED) {
            throw new BusinessException("Unpublish target must be INACTIVE or SUSPENDED.",
                    HttpStatus.BAD_REQUEST);
        }
        PlatformHotel hotel = require(publicId);
        hotel.setStatus(target);
        hotelRepository.save(hotel);

        // Stop new sales, keep the history. The projection row survives because quotations and
        // bookings reference it; only its bookability changes (design doc §6.6).
        List<Hotel> projections = tenantHotelRepository.findProjectionsAcrossTenants(publicId);
        for (Hotel projection : projections) {
            projection.setMarketplaceBookable(false);
            projection.setSyncStatus(HotelSyncStatus.SOURCE_INACTIVE);
            tenantHotelRepository.save(projection);
        }

        audit(target == PlatformHotelStatus.SUSPENDED
                        ? PlatformAuditAction.PLATFORM_HOTEL_UNPUBLISH
                        : PlatformAuditAction.PLATFORM_HOTEL_UNPUBLISH,
                hotel,
                "Unpublished " + hotel.getName() + " → " + target
                        + " (" + projections.size() + " tenant projections marked SOURCE_INACTIVE)"
                        + (reason == null || reason.isBlank() ? "" : " — " + reason));
        return get(publicId);
    }

    @Override
    @Transactional
    public void delete(UUID publicId) {
        PlatformHotel hotel = require(publicId);

        // Soft-deleting a hotel tenants have imported would leave those projections pointing at
        // nothing, with no way for the tenant to understand why. Unpublish is the correct verb.
        long linked = tenantHotelRepository.countProjectionsAcrossTenants(publicId);
        if (linked > 0) {
            throw new BusinessException(
                    hotel.getName() + " is imported by " + linked
                            + " tenant(s). Unpublish it instead of deleting.",
                    HttpStatus.CONFLICT);
        }

        hotel.softDelete(null);
        hotelRepository.save(hotel);
        audit(PlatformAuditAction.PLATFORM_HOTEL_DELETE, hotel, "Deleted catalog hotel " + hotel.getName());
    }

    // ── Rooms ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PlatformRoomDto addRoom(UUID hotelPublicId, PlatformRoomRequest request) {
        PlatformHotel hotel = require(hotelPublicId);
        PlatformHotelRoom room = new PlatformHotelRoom();
        room.setHotel(hotel);
        apply(request, room);
        PlatformHotelRoom saved = roomRepository.save(room);
        touch(hotel, "Added room " + saved.getName());
        return mapper.toRoomDto(saved);
    }

    @Override
    @Transactional
    public PlatformRoomDto updateRoom(UUID hotelPublicId, UUID roomPublicId, PlatformRoomRequest request) {
        PlatformHotel hotel = require(hotelPublicId);
        PlatformHotelRoom room = roomRepository
                .findByPublicIdAndHotelIdAndDeletedAtIsNull(roomPublicId, hotel.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Room not found: " + roomPublicId));
        apply(request, room);
        PlatformHotelRoom saved = roomRepository.save(room);
        touch(hotel, "Updated room " + saved.getName());
        return mapper.toRoomDto(saved);
    }

    @Override
    @Transactional
    public void deleteRoom(UUID hotelPublicId, UUID roomPublicId) {
        PlatformHotel hotel = require(hotelPublicId);
        PlatformHotelRoom room = roomRepository
                .findByPublicIdAndHotelIdAndDeletedAtIsNull(roomPublicId, hotel.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Room not found: " + roomPublicId));
        room.softDelete(null);
        roomRepository.save(room);
        touch(hotel, "Removed room " + room.getName());
    }

    // ── Meal plans ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PlatformMealPlanDto addMealPlan(UUID hotelPublicId, PlatformMealPlanRequest request) {
        PlatformHotel hotel = require(hotelPublicId);
        PlatformHotelMealPlan plan = new PlatformHotelMealPlan();
        plan.setHotel(hotel);
        apply(request, plan);
        PlatformHotelMealPlan saved = mealPlanRepository.save(plan);
        touch(hotel, "Added meal plan " + saved.getCode());
        return mapper.toMealPlanDto(saved);
    }

    @Override
    @Transactional
    public PlatformMealPlanDto updateMealPlan(UUID hotelPublicId, UUID mealPlanPublicId,
                                              PlatformMealPlanRequest request) {
        PlatformHotel hotel = require(hotelPublicId);
        PlatformHotelMealPlan plan = mealPlanRepository
                .findByPublicIdAndHotelIdAndDeletedAtIsNull(mealPlanPublicId, hotel.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Meal plan not found: " + mealPlanPublicId));
        apply(request, plan);
        PlatformHotelMealPlan saved = mealPlanRepository.save(plan);
        touch(hotel, "Updated meal plan " + saved.getCode());
        return mapper.toMealPlanDto(saved);
    }

    @Override
    @Transactional
    public void deleteMealPlan(UUID hotelPublicId, UUID mealPlanPublicId) {
        PlatformHotel hotel = require(hotelPublicId);
        PlatformHotelMealPlan plan = mealPlanRepository
                .findByPublicIdAndHotelIdAndDeletedAtIsNull(mealPlanPublicId, hotel.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Meal plan not found: " + mealPlanPublicId));
        plan.softDelete(null);
        mealPlanRepository.save(plan);
        touch(hotel, "Removed meal plan " + plan.getCode());
    }

    // ── internals ───────────────────────────────────────────────────────────

    private PlatformHotel require(UUID publicId) {
        return hotelRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Catalog hotel not found: " + publicId));
    }

    /** A child changed, so what a tenant would copy changed: bump the parent and record it. */
    private void touch(PlatformHotel hotel, String description) {
        hotel.bumpCatalogVersion();
        hotelRepository.save(hotel);
        markProjectionsStale(hotel, false);
        audit(PlatformAuditAction.PLATFORM_HOTEL_UPDATE, hotel,
                description + " on " + hotel.getName() + " → version " + hotel.getCatalogVersion());
    }

    /**
     * Flag every tenant projection of this hotel for re-sync. The counterpart of every
     * {@code bumpCatalogVersion()} in this class — a bump nobody is told about is a correction that
     * reaches a tenant only if somebody happens to open the hotel (design doc §6.6).
     *
     * <p><b>Only a healthy projection is merely "behind".</b> {@code LOCATION_MAPPING_REQUIRED} and
     * {@code SOURCE_INACTIVE} are unresolved problems, not versions: overwriting either with
     * {@code STALE} would erase the one signal that a hotel could not be placed in that tenant's
     * geography, leaving it looking like a routine lag while it is in fact absent from their master.
     * A null status is a row written before these columns existed and is safe to refresh.</p>
     *
     * @param sourceRepublished true only on publish, where the source coming back IS the resolution
     *                          of the {@code SOURCE_INACTIVE} that unpublish left behind. Nothing
     *                          else in this class may clear that flag.
     */
    private void markProjectionsStale(PlatformHotel hotel, boolean sourceRepublished) {
        int marked = 0;
        for (Hotel projection : tenantHotelRepository.findProjectionsAcrossTenants(hotel.getPublicId())) {
            HotelSyncStatus current = projection.getSyncStatus();
            boolean refreshable = current == null
                    || current == HotelSyncStatus.SYNCED
                    || (sourceRepublished && current == HotelSyncStatus.SOURCE_INACTIVE);

            if (sourceRepublished) {
                projection.setMarketplaceBookable(true);
            }
            if (refreshable) {
                projection.setSyncStatus(HotelSyncStatus.STALE);
                marked++;
            }
            tenantHotelRepository.save(projection);
        }
        if (marked > 0) {
            log.debug("Catalog hotel {} → v{}: {} tenant projection(s) marked STALE",
                    hotel.getPublicId(), hotel.getCatalogVersion(), marked);
        }
    }

    /** Catalog rows are global: there is no target tenant, so those columns are deliberately null. */
    private void audit(PlatformAuditAction action, PlatformHotel hotel, String description) {
        auditRecorder.safeRecord(action, true, null, null,
                TARGET_TYPE, hotel.getPublicId(), description);
    }

    private void apply(PlatformHotelRequest r, PlatformHotel h) {
        h.setName(trim(r.getName()));
        h.setCountryCode(upper(trim(r.getCountryCode())));
        h.setStateName(trim(r.getStateName()));
        h.setCityName(trim(r.getCityName()));
        h.setCityCode(upper(trim(r.getCityCode())));
        h.setAddress(trim(r.getAddress()));
        h.setLatitude(r.getLatitude());
        h.setLongitude(r.getLongitude());
        h.setStars(r.getStars());
        h.setRating(r.getRating());
        h.setWebsite(trim(r.getWebsite()));
        h.setMapUrl(trim(r.getMapUrl()));
        h.setOverview(r.getOverview());
        h.setPrimaryImageUrl(trim(r.getPrimaryImageUrl()));
        h.setPhone(trim(r.getPhone()));
        h.setEmail(trim(r.getEmail()));
        h.setSupplierVendorPublicId(r.getSupplierVendorPublicId());
        // Replace in place — reassigning the list detaches Hibernate's managed collection.
        h.getAmenities().clear();
        if (r.getAmenities() != null) {
            h.getAmenities().addAll(r.getAmenities().stream()
                    .map(PlatformHotelCatalogServiceImpl::trim)
                    .filter(a -> a != null && !a.isEmpty())
                    .toList());
        }
    }

    private void apply(PlatformRoomRequest r, PlatformHotelRoom room) {
        room.setName(trim(r.getName()));
        room.setMaxAdults(r.getMaxAdults());
        room.setMaxChildren(r.getMaxChildren());
        room.setMaxOccupancy(r.getMaxOccupancy());
        room.setBedType(trim(r.getBedType()));
        room.setSize(trim(r.getSize()));
        room.setDescription(r.getDescription());
        if (r.getActive() != null) {
            room.setActive(r.getActive());
        }
        List<String> images = new ArrayList<>();
        if (r.getImages() != null) {
            images.addAll(r.getImages().stream().map(PlatformHotelCatalogServiceImpl::trim)
                    .filter(i -> i != null && !i.isEmpty()).toList());
        }
        room.getImages().clear();
        room.getImages().addAll(images);
    }

    private void apply(PlatformMealPlanRequest r, PlatformHotelMealPlan plan) {
        plan.setCode(r.getCode());
        // Blank name ⇒ the code's own label, so a plan never renders as a bare acronym.
        String name = trim(r.getName());
        plan.setName(name == null || name.isEmpty() ? r.getCode().defaultLabel() : name);
        plan.setDescription(r.getDescription());
        if (r.getActive() != null) {
            plan.setActive(r.getActive());
        }
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase();
    }

    /** Kept for readability at call sites that stamp a sync timestamp. */
    static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
