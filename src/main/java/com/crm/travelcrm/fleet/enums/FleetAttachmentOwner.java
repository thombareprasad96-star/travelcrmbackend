package com.crm.travelcrm.fleet.enums;

/**
 * What a fleet attachment is evidence FOR.
 *
 * <p>Three owners, because three different papers exist in the field: the crumpled toll/fuel
 * receipt behind an expense row, the scanned certificate behind a compliance document, and the
 * photographed signed settlement sheet — the driver's own signature on his hisaab, which is what
 * ends the "I never agreed to that" dispute six months later.
 */
public enum FleetAttachmentOwner {

    /** Receipt / bill / challan image behind one expense row. */
    EXPENSE("Expense receipt"),

    /** Scan of the certificate a compliance document describes. */
    DOCUMENT("Document scan"),

    /** The signed settlement sheet — photographed after the driver signs. */
    SETTLEMENT("Settlement sheet");

    private final String label;

    FleetAttachmentOwner(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
