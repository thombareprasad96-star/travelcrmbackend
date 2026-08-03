package com.crm.travelcrm.master.hotel;

/**
 * Where a row in the tenant's private Hotel Master came from.
 *
 * <p>This is the flag that decides whether a field is the tenant's to edit. A {@link #TENANT} row is
 * theirs entirely; a {@link #PLATFORM_SYNC} row is a controlled projection of a catalog hotel, and
 * the platform owns its descriptive fields — a normal update must not silently break the link by
 * overwriting them (design doc §6.4).</p>
 *
 * <p>Every row that existed before the marketplace is {@link #TENANT}: the column defaults to it and
 * the migration backfills it. Nothing is adopted into the catalog automatically.</p>
 */
public enum HotelOrigin {

    /** Typed by the tenant. Fully theirs; the marketplace never touches it. */
    TENANT,

    /** A projection of a platform catalog hotel. Descriptive fields are platform-owned. */
    PLATFORM_SYNC
}
