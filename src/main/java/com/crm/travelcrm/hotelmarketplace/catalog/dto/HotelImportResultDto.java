package com.crm.travelcrm.hotelmarketplace.catalog.dto;

import com.crm.travelcrm.master.hotel.HotelSyncStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * Outcome of importing (or re-syncing) a catalog hotel into the tenant's own Hotel Master.
 *
 * <p>{@link #syncStatus} is the field that matters: an import can legitimately succeed with
 * {@link HotelSyncStatus#LOCATION_MAPPING_REQUIRED}, meaning the row exists but its geography could
 * not be resolved and a human must place it. Reporting that as a success with a flag beats both
 * alternatives — failing the import outright, or silently attaching the hotel to some arbitrary
 * city, which reads as correct everywhere downstream.</p>
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HotelImportResultDto {

    /** The tenant's Hotel Master row — created, or the existing one if already imported. */
    UUID tenantHotelPublicId;

    UUID platformHotelPublicId;
    String name;

    /** True when this call created the row; false when it re-synced an existing projection. */
    boolean created;

    HotelSyncStatus syncStatus;
    Long platformCatalogVersion;

    /** Human-readable note when the sync could not fully complete. Null on a clean import. */
    String message;
}
