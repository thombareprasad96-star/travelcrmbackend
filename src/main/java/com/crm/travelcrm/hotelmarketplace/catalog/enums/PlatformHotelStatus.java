package com.crm.travelcrm.hotelmarketplace.catalog.enums;

/**
 * Lifecycle of a catalog hotel, controlled entirely by the SuperAdmin.
 *
 * <p>Only {@link #ACTIVE} is sellable. Everything else is invisible to tenant search — but none of
 * them ever hides an already-imported projection or a historical booking: unpublishing stops NEW
 * business, it does not rewrite the past (design doc §6.6).</p>
 */
public enum PlatformHotelStatus {

    /** Being prepared. Never appears in tenant search, however complete the record looks. */
    DRAFT,

    /** Published and searchable by every entitled tenant. The only sellable state. */
    ACTIVE,

    /** Withdrawn from sale by the SuperAdmin. Linked tenant projections become SOURCE_INACTIVE. */
    INACTIVE,

    /** Withdrawn for cause (supplier dispute, compliance). Same visibility as INACTIVE; kept
     *  distinct so the reason survives in the record rather than only in an audit line. */
    SUSPENDED;

    /** Whether a tenant may see and import this hotel. */
    public boolean isSellable() {
        return this == ACTIVE;
    }
}
