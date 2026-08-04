package com.crm.travelcrm.fleet.enums;

/**
 * Whose money left the building. This is the single most important field on a fleet expense, because
 * it decides whether the row also moves the driver's cash balance.
 *
 * <p>{@link #DRIVER_CASH} is the only value that touches {@code fleet_cash_entries}: the driver
 * spent from the advance (peshgi) he was handed, so the row reduces what he owes back. The other two
 * are pure cost — the office already paid, or a vendor will bill later.
 */
public enum FleetPaidBy {

    /** Office paid directly — card, UPI, bank transfer, FASTag wallet, standing account at a pump. */
    OFFICE_DIRECT("Office paid"),

    /** Driver paid from his cash advance. Reduces his outstanding imprest balance. */
    DRIVER_CASH("Driver's cash"),

    /** Nothing has moved yet — a vendor/garage will raise a bill. Cost now, payment later. */
    VENDOR_CREDIT("On vendor credit");

    private final String label;

    FleetPaidBy(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** True when this expense must also post against the driver's running cash balance. */
    public boolean movesDriverCash() {
        return this == DRIVER_CASH;
    }
}
