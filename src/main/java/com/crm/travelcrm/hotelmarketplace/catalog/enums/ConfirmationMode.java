package com.crm.travelcrm.hotelmarketplace.catalog.enums;

/**
 * How a booking request against this hotel reaches a confirmed state.
 *
 * <p>The first release confirms everything by hand, so {@link #SUPERADMIN_APPROVAL} is the only
 * value ever written. {@link #INSTANT} exists so the column and its CHECK constraint do not have to
 * change when automated confirmation arrives — widening a CHECK later is the step that gets
 * forgotten and fails an INSERT in production.</p>
 */
public enum ConfirmationMode {

    /** A human SuperAdmin verifies availability and price, then confirms. The only mode in use. */
    SUPERADMIN_APPROVAL,

    /** Reserved: confirmed automatically against controlled allotment. Not implemented. */
    INSTANT
}
