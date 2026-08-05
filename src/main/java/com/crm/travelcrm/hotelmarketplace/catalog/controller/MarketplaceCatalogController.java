package com.crm.travelcrm.hotelmarketplace.catalog.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.hotelmarketplace.catalog.dto.HotelImportResultDto;
import com.crm.travelcrm.hotelmarketplace.catalog.dto.MarketplaceHotelDetailDto;
import com.crm.travelcrm.hotelmarketplace.catalog.dto.MarketplaceHotelSummaryDto;
import com.crm.travelcrm.hotelmarketplace.catalog.service.MarketplaceCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * The tenant's view of the platform hotel catalog: search it, look at one hotel, import it into
 * their own Hotel Master.
 *
 * <p>Everything under {@code /api/hotel-marketplace} is gated on the {@code HOTEL_MARKETPLACE}
 * module by {@code ModuleAccessFilter}, so a tenant whose plan excludes the add-on gets
 * {@code MODULE_NOT_ENABLED} before any handler here runs. The per-endpoint permissions below are
 * the second gate — module answers "did this tenant buy it", permission answers "may this user".</p>
 *
 * <p>There is no write path to the catalog here, and there cannot be: this controller only reaches
 * {@link MarketplaceCatalogService}, which returns marketplace DTOs and has no mutating catalog
 * method at all.</p>
 */
@RestController
@RequestMapping("/api/hotel-marketplace")
@RequiredArgsConstructor
public class MarketplaceCatalogController {

    private final MarketplaceCatalogService marketplaceService;

    @GetMapping("/hotels")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN','CRM_FULL','HOTEL_MARKETPLACE_VIEW')")
    public ResponseEntity<PagedApiResponse<MarketplaceHotelSummaryDto>> search(
            @RequestParam(defaultValue = "0")    int page,
            @RequestParam(defaultValue = "24")   int size,
            @RequestParam(defaultValue = "name") String sortBy,
            @RequestParam(defaultValue = "asc")  String sortDir,
            @RequestParam(required = false)      String q,
            @RequestParam(required = false)      String city,
            @RequestParam(required = false)      String countryCode,
            @RequestParam(required = false)      Integer minStars) {

        Sort sort = Sort.by("desc".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC, sortBy);
        Page<MarketplaceHotelSummaryDto> result = marketplaceService.search(
                q, city, countryCode, minStars, PageRequest.of(Math.max(page, 0), clamp(size), sort));

        return ResponseEntity.ok(PagedApiResponse.of(
                "Marketplace hotels fetched", result.getContent(),
                PaginationMeta.from(result, sortBy, sortDir)));
    }

    @GetMapping("/hotels/{publicId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN','CRM_FULL','HOTEL_MARKETPLACE_VIEW')")
    public ResponseEntity<ApiResponse<MarketplaceHotelDetailDto>> get(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success("Hotel fetched", marketplaceService.get(publicId)));
    }

    /**
     * Import into this tenant's Hotel Master, or refresh the projection they already hold.
     * Idempotent — a second call returns the same row, freshly synced.
     *
     * <p>Two paths, one handler. {@code /import} is what the frontend calls; {@code /sync-to-master}
     * is the name design doc §10.2 gives it, and is the more honest of the two — the operation is a
     * create-or-refresh, and "import" reads as something you do once. Both are kept rather than
     * renaming, because breaking a working client for a naming preference is not an improvement.</p>
     */
    @PostMapping({"/hotels/{publicId}/import", "/hotels/{publicId}/sync-to-master"})
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN','CRM_FULL','MASTER_MANAGE','HOTEL_MARKETPLACE_SYNC_MASTER')")
    public ResponseEntity<ApiResponse<HotelImportResultDto>> importToMaster(@PathVariable UUID publicId) {
        HotelImportResultDto result = marketplaceService.importToMaster(publicId);
        return ResponseEntity.ok(ApiResponse.success(
                result.isCreated() ? "Hotel imported to your masters" : "Hotel already imported — refreshed",
                result));
    }

    private static int clamp(int size) {
        return size < 1 ? 24 : Math.min(size, 100);
    }
}
