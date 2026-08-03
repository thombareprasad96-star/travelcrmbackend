package com.crm.travelcrm.hotelmarketplace.catalog.service;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.hotelmarketplace.catalog.dto.HotelImportResultDto;
import com.crm.travelcrm.hotelmarketplace.catalog.dto.MarketplaceHotelDetailDto;
import com.crm.travelcrm.hotelmarketplace.catalog.dto.MarketplaceHotelSummaryDto;
import com.crm.travelcrm.hotelmarketplace.catalog.entity.PlatformHotel;
import com.crm.travelcrm.hotelmarketplace.catalog.mapper.PlatformHotelMapper;
import com.crm.travelcrm.hotelmarketplace.catalog.repository.PlatformHotelMealPlanRepository;
import com.crm.travelcrm.hotelmarketplace.catalog.repository.PlatformHotelRepository;
import com.crm.travelcrm.hotelmarketplace.catalog.repository.PlatformHotelRoomRepository;
import com.crm.travelcrm.hotelmarketplace.sync.HotelMasterProjectionService;
import com.crm.travelcrm.master.hotel.Hotel;
import com.crm.travelcrm.master.hotel.HotelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What a TENANT may see of, and do with, the platform catalog.
 *
 * <p>Every read here goes through a repository method that pins {@code status = ACTIVE} inside the
 * query itself, so a new call site cannot forget the predicate and expose DRAFT rows. And every DTO
 * it returns is a marketplace type — the admin DTO physically cannot be reached from here, which is
 * what keeps the supplier link and catalog version off a tenant response (design doc §11).</p>
 *
 * <p>There is no tenant filter to lean on: catalog rows carry no {@code tenant_id}. The only place
 * the tenant matters is the projection lookup, and that is passed explicitly.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketplaceCatalogService {

    private final PlatformHotelRepository hotelRepository;
    private final PlatformHotelRoomRepository roomRepository;
    private final PlatformHotelMealPlanRepository mealPlanRepository;
    private final HotelRepository tenantHotelRepository;
    private final HotelMasterProjectionService projectionService;
    private final PlatformHotelMapper mapper;

    @Transactional(readOnly = true)
    public Page<MarketplaceHotelSummaryDto> search(String q, String city, String countryCode,
                                                   Integer minStars, Pageable pageable) {
        Long tenantId = requireTenantId();
        // Normalised HERE, not in the query — searchSellable's javadoc explains why a parameter
        // wrapped in LOWER()/CONCAT() blows up on `lower(bytea)` when it is null.
        Page<PlatformHotel> page = hotelRepository.searchSellable(
                containsLower(q), lowerOrNull(city), upperOrNull(countryCode),
                minStars, pageable);

        // One query for the whole page's import state, rather than one per row.
        Map<UUID, UUID> imported = importedProjections(tenantId,
                page.getContent().stream().map(PlatformHotel::getPublicId).toList());

        return page.map(h -> mapper.toMarketplaceSummary(
                h,
                roomRepository.findByHotelIdAndActiveTrueAndDeletedAtIsNullOrderByNameAsc(h.getId()).size(),
                imported.get(h.getPublicId())));
    }

    /**
     * Detail of one sellable hotel.
     *
     * <p>Opportunistic re-sync: if this tenant already holds a projection and the catalog has moved
     * on, refresh it here. That is the "lazy synchronisation plus version reconciliation" of design
     * doc §6.1 — no scheduled job has to have run for the tenant to see current data, and a tenant
     * who never opens a hotel never pays for syncing it.</p>
     */
    @Transactional
    public MarketplaceHotelDetailDto get(UUID publicId) {
        Long tenantId = requireTenantId();
        PlatformHotel hotel = requireSellable(publicId);

        UUID projectionPublicId = projectionService.findProjection(publicId, tenantId)
                .map(existing -> {
                    projectionService.syncIfStale(existing, hotel, tenantId);
                    return existing.getPublicId();
                })
                .orElse(null);

        return mapper.toMarketplaceDetail(
                hotel,
                roomRepository.findByHotelIdAndActiveTrueAndDeletedAtIsNullOrderByNameAsc(hotel.getId()),
                mealPlanRepository.findByHotelIdAndActiveTrueAndDeletedAtIsNullOrderByCodeAsc(hotel.getId()),
                projectionPublicId);
    }

    /**
     * Import into this tenant's own Hotel Master, or refresh an existing projection.
     *
     * <p>Deliberately idempotent rather than a hard 409 on a second call: the partial unique index
     * guarantees one projection per tenant per catalog hotel, so the honest answer to "import this
     * again" is to hand back the row they already have, freshly synced.</p>
     */
    @Transactional
    public HotelImportResultDto importToMaster(UUID publicId) {
        Long tenantId = requireTenantId();
        PlatformHotel hotel = requireSellable(publicId);
        return projectionService.importOrSync(hotel, tenantId);
    }

    // ── internals ───────────────────────────────────────────────────────────

    /** ACTIVE-only lookup. A DRAFT or unpublished hotel is a 404 — never "exists but forbidden". */
    private PlatformHotel requireSellable(UUID publicId) {
        return hotelRepository.findSellableByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Hotel not found: " + publicId));
    }

    private Map<UUID, UUID> importedProjections(Long tenantId, List<UUID> platformPublicIds) {
        if (platformPublicIds.isEmpty()) {
            return Map.of();
        }
        return tenantHotelRepository
                .findByTenantIdAndPlatformHotelPublicIdInAndDeletedAtIsNull(tenantId, platformPublicIds)
                .stream()
                .collect(Collectors.toMap(
                        Hotel::getPlatformHotelPublicId,
                        Hotel::getPublicId,
                        // The partial unique index makes a collision impossible; keep the first
                        // rather than throw, so a legacy duplicate cannot break the whole search page.
                        (a, b) -> a));
    }

    private static Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "TenantContext is empty — the marketplace is a tenant-facing surface.");
        }
        return tenantId;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String lowerOrNull(String s) {
        String v = blankToNull(s);
        return v == null ? null : v.toLowerCase();
    }

    private static String upperOrNull(String s) {
        String v = blankToNull(s);
        return v == null ? null : v.toUpperCase();
    }

    /**
     * Free-text needle for a {@code LIKE} against an already-lower-cased column. Exactly what
     * {@code LOWER(CONCAT('%', :q, '%'))} produced before — same matches, same non-matches.
     *
     * <p>A {@code %} or {@code _} typed by the user is still a wildcard, as it always was here.
     * Escaping them is NOT a one-line change: Hibernate renders this {@code LIKE} with
     * {@code ESCAPE ''}, which tells PostgreSQL there is no escape character, so a backslash would
     * be matched literally rather than escaping anything. Fixing it means setting an explicit
     * escape clause first — a separate change, not a side effect of this one.</p>
     */
    private static String containsLower(String s) {
        String v = lowerOrNull(s);
        return v == null ? null : "%" + v + "%";
    }
}
