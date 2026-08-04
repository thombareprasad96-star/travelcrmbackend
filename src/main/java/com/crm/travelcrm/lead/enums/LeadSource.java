package com.crm.travelcrm.lead.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Where a lead came from. See {@link LeadOrigin} for <em>how</em> it got here — the two are
 * independent, and both are needed: {@code GOOGLE_ADS} and {@code WHATSAPP} are deliberately both
 * human-selectable and machine-stamped, so this enum alone cannot say whether a human typed it or an
 * integration asserted it.
 *
 * <p><b>The wire format is {@link #getDisplayName()}, not {@link #name()}</b> — {@code @JsonValue}
 * sits on the getter, so the API emits {@code "Google Ads"} and never {@code GOOGLE_ADS}. Reads are
 * display-form only; writes are lenient ({@link #fromValue} accepts either vocabulary,
 * case-insensitively). That asymmetry is a trap: anything built on the obvious {@code Enum::name}
 * idiom POSTs fine and then fails to match on read. The metadata endpoint's {@code value} must be
 * the displayName.
 *
 * <p><b>Never remove a constant.</b> {@code @Enumerated(STRING)} maps the stored string back and
 * {@link #fromValue} throws on an unknown value, so deleting one breaks <em>reading</em> every
 * historical lead that carries it. Retire with {@link SourceSelectability#LEGACY_READ_ONLY} instead.
 *
 * <p><b>Adding a constant is a two-file change.</b> This enum, plus the {@code leads_lead_source_check}
 * refresh block in {@code db/indexes.sql} — Hibernate generated that constraint when it first created
 * the table and {@code ddl-auto=update} never alters it, so a new constant is rejected at the DB
 * until the block is refreshed. {@code SchemaEnumConstraintValidator} refuses to start if the two
 * drift, which is the only thing that makes the failure visible: {@code spring.sql.init} runs with
 * {@code continue-on-error=true}, so a failed {@code ADD CONSTRAINT} leaves NO constraint and logs
 * nothing.
 */
public enum LeadSource {

    // ── The original nine. Declaration order preserved: fromValue returns the FIRST match, so
    // reordering these could change which constant an ambiguous input resolves to. ──
    SOCIAL_MEDIA("Social Media", SourceSelectability.MANUAL_SELECTABLE),
    WEBSITE("Website", SourceSelectability.MANUAL_SELECTABLE),
    GOOGLE_ADS("Google Ads", SourceSelectability.MANUAL_SELECTABLE),
    /** Superseded by {@link #META_ADS} / {@link #FB_MESSENGER}; kept readable for historical leads. */
    FACEBOOK("Facebook", SourceSelectability.LEGACY_READ_ONLY),
    /**
     * Must stay declared BEFORE {@link #INSTAGRAM_DM}: {@code fromValue("Instagram")} takes the first
     * match, and that has to be this one.
     */
    INSTAGRAM("Instagram", SourceSelectability.LEGACY_READ_ONLY),
    WHATSAPP("WhatsApp", SourceSelectability.MANUAL_SELECTABLE),
    REFERRAL("Referral", SourceSelectability.MANUAL_SELECTABLE),
    /** Superseded by {@link #PHONE_MANUAL} (human) and {@link #IVR_CALL} (telephony integration). */
    DIRECT_CALL("Direct Call", SourceSelectability.LEGACY_READ_ONLY),
    OTHER("Other", SourceSelectability.MANUAL_SELECTABLE),

    // ── Manual / internal ──
    MANUAL("Manual Entry", SourceSelectability.MANUAL_SELECTABLE),
    WALK_IN("Walk-in", SourceSelectability.MANUAL_SELECTABLE),
    PHONE_MANUAL("Phone (Manual)", SourceSelectability.MANUAL_SELECTABLE),
    /** Selectable so a human can tag a marketplace we have no adapter for; an adapter may also stamp it. */
    TRAVEL_MARKETPLACE("Travel Marketplace", SourceSelectability.MANUAL_SELECTABLE),

    // ── Marketplace webhooks ──
    JUSTDIAL("JustDial", SourceSelectability.MACHINE_ONLY),
    INDIAMART("IndiaMART", SourceSelectability.MACHINE_ONLY),
    TRADEINDIA("TradeIndia", SourceSelectability.MACHINE_ONLY),
    SULEKHA("Sulekha", SourceSelectability.MACHINE_ONLY),

    // ── Ad platforms & conversational ──
    META_ADS("Meta Ads", SourceSelectability.MACHINE_ONLY),
    INSTAGRAM_DM("Instagram DM", SourceSelectability.MACHINE_ONLY),
    FB_MESSENGER("Facebook Messenger", SourceSelectability.MACHINE_ONLY),

    // ── Telephony ──
    IVR_CALL("IVR Call", SourceSelectability.MACHINE_ONLY),

    // ── Owned surfaces ──
    WEBSITE_FORM("Website Form", SourceSelectability.MACHINE_ONLY),
    WEBLINK_ENQUIRY("Weblink Enquiry", SourceSelectability.MACHINE_ONLY),

    // ── System-generated (LeadOrigin.SYSTEM — no integration row behind them) ──
    SUB_AGENT("Sub-Agent", SourceSelectability.MACHINE_ONLY),
    /**
     * "Repeat Enquiry", NOT "Repeat Customer": {@code LeadType.REPEAT_CUSTOMER} already displays as
     * "Repeat Customer", and the create form shows both dropdowns. Two identical strings meaning
     * different things (who the customer is vs how they arrived) is a support ticket waiting to happen.
     */
    REPEAT_CUSTOMER("Repeat Enquiry", SourceSelectability.MACHINE_ONLY);

    private final String displayName;
    private final SourceSelectability selectability;

    LeadSource(String displayName, SourceSelectability selectability) {
        this.displayName = displayName;
        this.selectability = selectability;
    }

    /**
     * Two constants sharing a displayName would compile fine and silently mis-resolve in
     * {@link #fromValue} — the first declaration wins and the second becomes unreachable by name,
     * which reads as "the dropdown saves the wrong source sometimes". Fail at class-load instead.
     */
    static {
        Set<String> seen = new HashSet<>();
        String dupe = Arrays.stream(values())
                .map(s -> s.displayName.toLowerCase())
                .filter(d -> !seen.add(d))
                .findFirst()
                .orElse(null);
        if (dupe != null) {
            throw new IllegalStateException(
                    "LeadSource has two constants with the display name '" + dupe
                            + "'. fromValue() would silently resolve to the first one.");
        }
    }

    @JsonValue
    public String getDisplayName() {
        return displayName;
    }

    /** Whether a human may pick this in the dropdown. Drives the metadata endpoint. */
    public SourceSelectability getSelectability() {
        return selectability;
    }

    @JsonCreator
    public static LeadSource fromValue(String value) {
        // Blank joins null in yielding null. An untouched dropdown posts "", and throwing on it
        // happens during Jackson deserialization — before bean validation — so the client got an
        // opaque HttpMessageNotReadableException 400 carrying no fieldErrors, and the friendly
        // @NotNull("Lead source is required") could never fire.
        if (value == null || value.isBlank()) return null;
        for (LeadSource source : values()) {
            if (source.displayName.equalsIgnoreCase(value) || source.name().equalsIgnoreCase(value)) {
                return source;
            }
        }
        throw new IllegalArgumentException("Unknown LeadSource: " + value);
    }
}
