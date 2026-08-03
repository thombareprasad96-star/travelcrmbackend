package com.crm.travelcrm.fleet.enums;

/**
 * Every paper a check-post, an RTO or a border post can ask for.
 *
 * <p><b>Why this replaces four date columns.</b> {@code FleetVehicle} carried
 * {@code insuranceExpiry}, {@code rcExpiry}, {@code permitExpiry} and {@code pucExpiry} — one column
 * per document, one value each. That shape cannot hold a renewal history (the new certificate
 * overwrites the old, and with it the number, the authority and what it cost), cannot represent a
 * vehicle carrying both a national AND a state permit, and has no room at all for the papers an
 * operator is actually stopped for.
 *
 * <p><b>The list is not generic.</b> Practitioners named these specifically: the Uttarakhand Green
 * Card and Trip Card for Char Dham work, the VLTD/panic button and speed governor that make a
 * commercial vehicle legal to run at all, and on the driver side the transport endorsement, PSV
 * badge and medical — none of which fit in a "driving licence expiry" field.
 *
 * <p>{@link #blocksByDefault()} is a DEFAULT, overridable per document. The split matters: a
 * dispatcher was emphatic that compliance must warn and not block, while the accountant was equally
 * emphatic that an expired PSV badge must refuse the assignment. Both are right about different
 * papers, so the paper decides, and an owner can override with a recorded reason.
 */
public enum FleetDocumentCategory {

    // ── Vehicle: the ones that stop it at a barrier ─────────────────────────
    INSURANCE               ("Insurance",                 Owner.VEHICLE, true,  "IN"),
    REGISTRATION_CERTIFICATE("Registration certificate",  Owner.VEHICLE, true,  "IN"),
    FITNESS_CERTIFICATE     ("Fitness certificate",       Owner.VEHICLE, true,  "IN"),
    PUC                     ("PUC certificate",           Owner.VEHICLE, false, "IN"),

    NATIONAL_PERMIT_INDIA   ("National permit",           Owner.VEHICLE, true,  "IN"),
    STATE_PERMIT_INDIA      ("State permit",              Owner.VEHICLE, true,  "IN"),
    ROAD_TAX_INDIA          ("Road tax",                  Owner.VEHICLE, true,  "IN"),
    GREEN_TAX_INDIA         ("Green tax",                 Owner.VEHICLE, false, "IN"),

    /** Char Dham / Uttarakhand hill work — no Green Card, no entry. Named by the owner lens. */
    UTTARAKHAND_GREEN_CARD  ("Green Card (Uttarakhand)",  Owner.VEHICLE, true,  "IN"),
    UTTARAKHAND_TRIP_CARD   ("Trip Card (Uttarakhand)",   Owner.VEHICLE, false, "IN"),

    /** Statutory fitment on commercial passenger vehicles; an RTO check fails without them. */
    VLTD_PANIC_BUTTON       ("VLTD / panic button",       Owner.VEHICLE, false, "IN"),
    SPEED_GOVERNOR          ("Speed governor",            Owner.VEHICLE, false, "IN"),

    // ── Vehicle: Nepal ──────────────────────────────────────────────────────
    PERMIT_NEPAL            ("Nepal permit",              Owner.VEHICLE, true,  "NP"),
    /** Customs entry. Carries a hard exit deadline, not just a validity — see the exit-date field. */
    BHANSAR_NEPAL           ("Bhansar (Nepal customs)",   Owner.VEHICLE, true,  "NP"),

    // ── Driver ──────────────────────────────────────────────────────────────
    DRIVING_LICENCE         ("Driving licence",           Owner.DRIVER,  true,  "IN"),
    /** A car licence does not permit a commercial vehicle — this is the endorsement that does. */
    TRANSPORT_ENDORSEMENT   ("Transport endorsement",     Owner.DRIVER,  true,  "IN"),
    PSV_BADGE               ("PSV badge",                 Owner.DRIVER,  true,  "IN"),
    MEDICAL_CERTIFICATE     ("Medical certificate",       Owner.DRIVER,  false, "IN"),

    OTHER                   ("Other",                     Owner.EITHER,  false, null);

    /** Which side of the fleet a category belongs to. */
    public enum Owner { VEHICLE, DRIVER, EITHER }

    private final String label;
    private final Owner owner;
    private final boolean blocksByDefault;
    private final String countryCode;

    FleetDocumentCategory(String label, Owner owner, boolean blocksByDefault, String countryCode) {
        this.label = label;
        this.owner = owner;
        this.blocksByDefault = blocksByDefault;
        this.countryCode = countryCode;
    }

    public String label() {
        return label;
    }

    public Owner owner() {
        return owner;
    }

    /**
     * Whether an expired document of this category should refuse an assignment rather than warn.
     * A per-document override wins over this; see {@code FleetComplianceDocument.blocking}.
     */
    public boolean blocksByDefault() {
        return blocksByDefault;
    }

    /** ISO country the paper is issued under, or null when it is not country-specific. */
    public String countryCode() {
        return countryCode;
    }

    public boolean appliesToVehicle() {
        return owner == Owner.VEHICLE || owner == Owner.EITHER;
    }

    public boolean appliesToDriver() {
        return owner == Owner.DRIVER || owner == Owner.EITHER;
    }

    /** Categories carrying a state jurisdiction — a state permit in UP is not one in Rajasthan. */
    public boolean needsState() {
        return this == STATE_PERMIT_INDIA || this == ROAD_TAX_INDIA || this == GREEN_TAX_INDIA;
    }

    /** Nepal entry: the vehicle must be back across the border by a date, independent of validity. */
    public boolean needsExitDeadline() {
        return this == BHANSAR_NEPAL;
    }
}
