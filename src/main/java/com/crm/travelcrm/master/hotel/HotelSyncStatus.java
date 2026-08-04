package com.crm.travelcrm.master.hotel;

/**
 * Health of the link between a {@link HotelOrigin#PLATFORM_SYNC} projection and its catalog source.
 * Always {@code null} on a tenant-authored row.
 */
public enum HotelSyncStatus {

    /** Projection matches the catalog at the version recorded on the row. */
    SYNCED,

    /** The catalog moved on. The next read re-syncs; nothing is wrong, it is just behind. */
    STALE,

    /**
     * The catalog hotel's country/city could not be resolved to anything in this tenant's geography,
     * and the sync refused to guess. Deliberately a visible state rather than a silent best-effort
     * attach: putting a Goa hotel under the first city of some unrelated destination produces data
     * that reads as correct everywhere downstream (design doc §6.5).
     */
    LOCATION_MAPPING_REQUIRED,

    /**
     * The catalog hotel was unpublished. The projection is kept — quotations and bookings reference
     * it — but it is no longer marketplace-bookable.
     */
    SOURCE_INACTIVE
}
