package com.crm.travelcrm.lead.enums;

/**
 * Whether a {@link LeadSource} may be chosen by a human, stamped by an integration, or neither.
 *
 * <p><b>Why three states and not two booleans.</b> A boolean pair cannot express "still readable,
 * but no longer offered for new tagging" — {@code selectable && !deprecated} is arithmetically
 * incapable of producing the intended set. The dropdown is exactly
 * {@code catalog.filter(o => o.selectability === 'MANUAL_SELECTABLE')}; anything else is a second
 * hardcoded list living somewhere, which is the bug this whole mechanism exists to kill.
 *
 * <p><b>Why not just hide the machine ones on the frontend.</b> Because that is precisely the live
 * bug the scan found: {@code LeadInformation.jsx} hardcodes 8 of the 9 real sources, so a lead
 * carrying the 9th matches no {@code <option>}, the select falls back, and saving the lead
 * <em>silently rewrites its source</em>. Serving selectability from the backend means the list can
 * never drift from the enum again.
 *
 * <p>This says nothing about how a <em>particular lead</em> was created — that is {@link LeadOrigin},
 * a column on the lead row. The two are independent: {@code GOOGLE_ADS} is MANUAL_SELECTABLE and can
 * still arrive with {@code origin = INTEGRATION}.
 */
public enum SourceSelectability {

    /** Offered in the create/edit dropdown. An integration may also stamp it. */
    MANUAL_SELECTABLE,

    /** Only an integration stamps it. A human picking "JustDial" by hand would make attribution a lie. */
    MACHINE_ONLY,

    /**
     * Historical. Existing leads keep it and display it; it is not offered for new tagging.
     * Deleting the constant is not an option — {@code @Enumerated(STRING)} maps the stored string
     * back and {@code LeadSource.fromValue} throws on unknown, so removing one breaks <em>reading</em>
     * every historical lead that carries it.
     */
    LEGACY_READ_ONLY
}
