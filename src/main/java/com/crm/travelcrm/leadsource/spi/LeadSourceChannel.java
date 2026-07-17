package com.crm.travelcrm.leadsource.spi;

import com.crm.travelcrm.lead.enums.LeadSource;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A channel the framework can ingest leads from — the registry key and the webhook URL segment.
 *
 * <p><b>Why this is not {@link LeadSource}.</b> {@code LeadSource} carries {@code @JsonValue} on
 * {@code getDisplayName()}, so its wire vocabulary is a <em>renameable display string</em>. A webhook
 * URL pasted into JustDial's console is effectively permanent — renaming a dropdown label must never
 * break live ingestion. Keeping them separate also makes {@code adapterFor(WALK_IN)} a compile-time
 * impossibility rather than a runtime throw: only things that CAN be ingested exist here.
 *
 * <p><b>{@link #slug()} is permanent.</b> It is in URLs held by third parties and stored in
 * {@code lead_source_integrations.channel}. Changing one silently 404s every live connection of that
 * channel. Adding a constant is free; renaming a slug is a migration.
 *
 * <p>Stored in the DB as a plain {@code VARCHAR} of {@link #slug()} — deliberately <b>not</b>
 * {@code @Enumerated}, with no CHECK constraint and no {@code db/indexes.sql} block. The framework's
 * goal is that adding a channel is "write one adapter"; an enum-backed column with a CHECK would make
 * it "write one adapter AND remember the constraint refresh, or the insert is rejected at the DB".
 */
public enum LeadSourceChannel {

    // ── Owned surfaces ──
    WEBSITE_FORM("website_form", LeadSource.WEBSITE_FORM),
    WEBLINK_ENQUIRY("weblink_enquiry", LeadSource.WEBLINK_ENQUIRY),

    // ── Marketplace webhooks ──
    JUSTDIAL("justdial", LeadSource.JUSTDIAL),
    INDIAMART("indiamart", LeadSource.INDIAMART),
    TRADEINDIA("tradeindia", LeadSource.TRADEINDIA),
    SULEKHA("sulekha", LeadSource.SULEKHA),

    // ── Ad platforms ──
    GOOGLE_ADS("google_ads", LeadSource.GOOGLE_ADS),
    META_ADS("meta_ads", LeadSource.META_ADS),

    // ── Conversational ──
    INSTAGRAM_DM("instagram_dm", LeadSource.INSTAGRAM_DM),
    FB_MESSENGER("fb_messenger", LeadSource.FB_MESSENGER),
    WHATSAPP("whatsapp", LeadSource.WHATSAPP),

    // ── Telephony ──
    IVR_CALL("ivr_call", LeadSource.IVR_CALL);

    private final String slug;
    private final LeadSource leadSource;

    LeadSourceChannel(String slug, LeadSource leadSource) {
        this.slug = slug;
        this.leadSource = leadSource;
    }

    /** Two channels sharing a slug would make registry lookup and URL routing ambiguous. */
    static {
        Set<String> seen = new HashSet<>();
        String dupe = Arrays.stream(values())
                .map(c -> c.slug)
                .filter(s -> !seen.add(s))
                .findFirst()
                .orElse(null);
        if (dupe != null) {
            throw new IllegalStateException(
                    "LeadSourceChannel has two constants with the slug '" + dupe + "'.");
        }
    }

    /** Lowercase, url-safe, <b>permanent</b>. The registry key and the webhook path segment. */
    public String slug() {
        return slug;
    }

    /**
     * The {@link LeadSource} a lead from this channel is stamped with.
     *
     * <p>The adapter never chooses this — otherwise a JustDial adapter could stamp {@code WEBSITE}.
     * Note several channels map to a {@code MANUAL_SELECTABLE} source ({@code GOOGLE_ADS},
     * {@code WHATSAPP}): what distinguishes machine from human there is {@code Lead.origin}, not the
     * source constant.
     */
    public LeadSource leadSource() {
        return leadSource;
    }

    /**
     * Resolve a URL path segment to a channel.
     *
     * <p>Returns {@link Optional#empty()} rather than throwing: an unknown segment is an unauthenticated
     * stranger hitting a public endpoint, which must 404 with zero database work, not raise.
     */
    public static Optional<LeadSourceChannel> fromSlug(String slug) {
        if (slug == null) return Optional.empty();
        String needle = slug.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(c -> c.slug.equals(needle)).findFirst();
    }
}
