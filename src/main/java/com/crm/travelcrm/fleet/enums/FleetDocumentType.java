package com.crm.travelcrm.fleet.enums;

/** Expirable compliance documents tracked by the expiry scan (vehicle docs + driver licence). */
public enum FleetDocumentType {

    INSURANCE("Insurance"),
    RC("Registration certificate (RC)"),
    PERMIT("Permit"),
    PUC("PUC certificate"),
    DRIVER_LICENSE("Driving licence");

    private final String label;

    FleetDocumentType(String label) {
        this.label = label;
    }

    /** Human-readable label used in notifications and dashboard rows. */
    public String label() {
        return label;
    }
}