package com.crm.travelcrm.fleet.enums;

/**
 * What a rupee was spent on. Stored as the enum NAME; the label is display only.
 *
 * <p><b>Toll and Parking are deliberately separate</b> — merging them into one "Toll Parking" type,
 * as was once proposed, makes every state-wise and per-vehicle report wrong, and they are collected
 * by different authorities in different ways (FASTag vs cash at a temple-town lot).
 *
 * <p><b>{@link #DRIVER_BATA} and {@link #NIGHT_HALT} are computed, not typed.</b> They are derived
 * from the allowance policy and the trip's duty days at settlement time. They exist as expense types
 * so they land in the same per-trip and per-vehicle COST total as everything else — a trip's real
 * cost includes what the driver was paid to be away from home.
 *
 * <p><b>They must NEVER be counted a second time in the driver's cash settlement.</b> The driver
 * keeps his bata out of the advance he is already holding, so it is discharged the moment the
 * settlement subtracts his entitlement. Counting the bata row again as driver cash spend
 * double-subtracts it: on an 8,000 advance with 5,000 of real spend and 1,200 of bata, the driver
 * would be asked to return 600 instead of 1,800 and would walk away with 1,200 he was never owed —
 * while the trip's cost total simultaneously reads 7,400 against 6,200 actually spent. The single
 * defence is {@link #isSystemComputed()}, and the settlement identity is defined against it:
 *
 * <pre>
 *   driverCashSpend  = SUM(base_amount) WHERE paid_by = DRIVER_CASH AND NOT type.isSystemComputed()
 *   netDueFromDriver = SUM(direction.signum * amount) - driverCashSpend - allowanceTotal
 * </pre>
 *
 * Any query that sums driver cash spend without that exclusion is a defect.
 *
 * <p><b>{@link #BORDER_AGENT_FEE} is an honest label for a real line item.</b> The agent/tout fee at
 * Sunauli or Raxaul is paid, is never receipted, and currently gets buried in "OTHER" or absorbed by
 * the driver. Naming it is what makes the cross-border cost report true.
 *
 * <p>{@code OTHER} carries no free-text category. A free-text escape hatch destroys the reporting
 * this enum exists for; if a new category is real, add a constant.
 */
public enum FleetExpenseType {

    TOLL             ("Toll",                  true),
    PARKING          ("Parking",               true),
    FUEL             ("Fuel / diesel",         true),
    MAINTENANCE      ("Repair & service",      true),
    TYRE             ("Tyres",                 true),

    // Cross-border (Nepal). Country is fixed by the type — never asked of the user.
    BHANSAR_NEPAL    ("Bhansar (Nepal customs)", false),
    PERMIT_NP        ("Nepal permit",          false),
    BORDER_AGENT_FEE ("Border agent fee",      false),

    // Statutory (India).
    PERMIT_IN        ("India permit",          true),
    ROAD_TAX_IN      ("India road tax",        true),
    CHALLAN          ("Challan / fine",        false),

    // Computed at settlement from FleetAllowancePolicy — not entered by hand.
    DRIVER_BATA      ("Driver allowance (bata)", false),
    NIGHT_HALT       ("Night halt",            false),

    OTHER            ("Other",                 true);

    private final String label;

    /**
     * Whether GST can normally appear on this line, so the entry form shows the tax block instead of
     * cluttering every row with it. Advisory only — the accountant can still record tax on any row.
     *
     * <p>Note this is NOT the same as input-credit eligibility: fuel carries GST but the credit is
     * blocked, and tolls are exempt outright. That call lives on the expense row's
     * {@code itcEligible} flag, which the accountant owns.
     */
    private final boolean gstLikely;

    FleetExpenseType(String label, boolean gstLikely) {
        this.label = label;
        this.gstLikely = gstLikely;
    }

    public String label() {
        return label;
    }

    public boolean gstLikely() {
        return gstLikely;
    }

    /** Nepal-side types: the trip's NPR rate applies and the country is fixed to NP. */
    public boolean isNepal() {
        return this == BHANSAR_NEPAL || this == PERMIT_NP || this == BORDER_AGENT_FEE;
    }

    /**
     * Types the server computes at settlement. A client may never post these directly — otherwise a
     * bata row typed by hand and a bata row computed from policy both land in the total.
     */
    public boolean isSystemComputed() {
        return this == DRIVER_BATA || this == NIGHT_HALT;
    }
}
