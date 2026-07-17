package com.crm.travelcrm.leadsource.adapter;

import com.crm.travelcrm.lead.enums.LeadSource;
import com.crm.travelcrm.leadsource.gateway.InboundVerifier;
import com.crm.travelcrm.leadsource.spi.ChannelCatalogEntry;
import com.crm.travelcrm.leadsource.spi.IdempotencyKey;
import com.crm.travelcrm.leadsource.spi.InboundParseResult;
import com.crm.travelcrm.leadsource.spi.InboundVerification;
import com.crm.travelcrm.leadsource.spi.LeadSourceAdapter;
import com.crm.travelcrm.leadsource.spi.LeadSourceChannel;
import com.crm.travelcrm.leadsource.spi.NormalizedLead;
import com.crm.travelcrm.leadsource.spi.RawInbound;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inbound WhatsApp — the "bhaiya package batao" message, via Interakt.
 *
 * <h2>WhatsApp is a FUNNEL, not a source. This adapter's whole design follows from that.</h2>
 *
 * <p>An Instagram bio, a Google Business listing, a referral and a Click-to-WhatsApp ad all arrive on
 * this same wire. Stamping every one of them {@code WHATSAPP} would be technically true and
 * commercially useless: every rupee of Meta spend would land in a bucket named after the medium it
 * travelled over, and "which channel should we fund?" would become unanswerable. So:
 *
 * <ul>
 *   <li><b>Meta {@code referral} present</b> → the payload PROVES a Click-to-WhatsApp ad drove this.
 *       Source is {@link LeadSource#META_ADS}, and the ad's ids go to {@code lead_attributions}.</li>
 *   <li><b>No referral</b> → {@link LeadSource#WHATSAPP}, which here means <b>"arrived through the
 *       WhatsApp funnel, TRUE SOURCE UNKNOWN"</b> — not "WhatsApp is the source". {@code origin} is
 *       stamped {@code INTEGRATION} by the pipeline, which is what keeps it distinguishable from a
 *       human who picked "WhatsApp" from the dropdown.</li>
 * </ul>
 *
 * <p><b>An unattributed lead stays visibly unattributed.</b> There is deliberately no keyword-sniffing
 * of the message, no sender-history lookup and no first-touch guess: those manufacture attribution
 * numbers that real ad budget is then spent against. Not knowing is a fact worth reporting honestly.
 *
 * <h2>What is verified here, and what is not</h2>
 *
 * <p><b>VERIFIED</b> (Interakt's own docs, two independently-fetched pages returning a byte-identical
 * example): the envelope {@code {version, timestamp, type, data:{customer, message}}}, the paths below,
 * and the {@code Interakt-Signature} HMAC — including its {@code sha256=} prefix, confirmed against
 * Interakt's published worked example. None of that is a guess.
 *
 * <p><b>NOT ESTABLISHED</b>: whether Interakt forwards Meta's {@code referral} at all. Interakt's
 * documented payload is a closed field list with no referral keys, and it makes no statement either way.
 * It demonstrably HOLDS the data (its CTWA Ad Launcher binds a chatbot to an ad; its Conversions-API
 * feature returns {@code ctwa_clid} to Meta) — but possession is not exposure. So {@link #readReferral}
 * PROBES the plausible paths and simply finds nothing if they are absent, which costs one null check and
 * degrades to the honest {@code WHATSAPP} answer.
 *
 * <p><b>Why shipping before that is settled is safe rather than reckless:</b> {@link #parse} is pure and
 * the raw payload is persisted, so a later live capture that reveals a referral block means re-running
 * the parser over stored deliveries and BACKFILLING attribution. No leads are lost in the meantime.
 * <b>One live Click-to-WhatsApp test click settles it in thirty minutes</b> — a few rupees of ad spend,
 * and the Recent deliveries panel shows the raw body.
 *
 * <p>If it turns out Interakt does NOT forward it, the honest answer is that WhatsApp-funnel ad ROI is
 * not recoverable from this BSP, and the fix is commercial (ask Interakt, or move Click-to-WhatsApp
 * numbers to Meta Cloud API direct) — not a heuristic in this file.
 */
@Component
public class WhatsAppInboundAdapter implements LeadSourceAdapter {

    // ── Interakt's envelope. VERIFIED against their published example. ────────
    private static final String EVENT_MESSAGE_RECEIVED = "message_received";

    /**
     * The inbound discriminator.
     *
     * <p>Interakt posts our OWN outbound messages back to this same URL too. Without this check the
     * agency's every reply would be parsed as a fresh enquiry from itself.
     */
    private static final String CHAT_TYPE_CUSTOMER = "CustomerMessage";

    /** Where Meta's referral MIGHT appear if Interakt forwards it. All unverified — see the javadoc. */
    private static final List<String[]> REFERRAL_PATHS = List.of(
            new String[]{"data", "message", "referral"},
            // meta_data is {} in Interakt's example — the likeliest carrier if they expose it at all.
            new String[]{"data", "message", "meta_data", "referral"},
            new String[]{"data", "referral"});

    private static final int MAX_EXTRAS = 12;

    @Override
    public LeadSourceChannel channel() {
        return LeadSourceChannel.WHATSAPP;
    }

    /**
     * Interakt signs every delivery. VERIFIED against their own worked example:
     * <pre>
     *   payload {"foo":1,"bar":2} + secret "examplekey"
     *   → Interakt-Signature: sha256=b84783d10ede5bd6ed771e8b16fbe5a7093340159d6e49ec4248350b6ec2c7b4
     * </pre>
     *
     * <p><b>The {@code sha256=} prefix is not cosmetic</b> — it is the exact detail the SPI warns about.
     * Omit it and the comparison fails for every honest delivery, 100% of the time, because the framework
     * compares the FULL expected string constant-time. Confirmed present in Interakt's example above.
     *
     * <p>This is the strongest verification of any channel here: unlike a marketplace token in a URL, a
     * forged delivery cannot be signed without the secret.
     */
    @Override
    public InboundVerification verification() {
        return new InboundVerification.HmacHeader(
                "Interakt-Signature", "HmacSHA256", InboundVerification.Enc.HEX, "sha256=");
    }

    /**
     * Interakt's own message id.
     *
     * <p>Their docs give a 3-second response SLA; a slow reply means a retry, and a retry without this
     * is a duplicate lead — or worse, a duplicate activity appended to a real customer's lead.
     */
    @Override
    public IdempotencyKey dedupKey(RawInbound in) {
        JsonNode json = in.json();
        return json == null ? IdempotencyKey.none() : IdempotencyKey.of(text(json, "data", "message", "id"));
    }

    @Override
    public InboundParseResult parse(RawInbound in) {
        JsonNode json = in.json();
        if (json == null || !json.isObject()) {
            return new InboundParseResult.Ignored("unparseable_body");
        }

        // Interakt dispatches by a field in the BODY — there is no event-name header — and this one URL
        // receives message_status, template callbacks and our own outbound echoes as well. All of that is
        // valid traffic that is not a lead: Ignored, never thrown, or a status callback becomes a retry
        // storm on a provider with a 3-second SLA.
        String type = text(json, "type");
        if (!EVENT_MESSAGE_RECEIVED.equals(type)) {
            return new InboundParseResult.Ignored("not_an_inbound_message");
        }

        String chatType = text(json, "data", "message", "chat_message_type");
        if (chatType != null && !CHAT_TYPE_CUSTOMER.equals(chatType)) {
            // Our own reply, echoed back. Parsing it would create a lead from the agency messaging itself.
            return new InboundParseResult.Ignored("not_a_customer_message");
        }

        String phone = text(json, "data", "customer", "channel_phone_number");
        if (phone == null) {
            return new InboundParseResult.Ignored("no_contact_details");
        }

        JsonNode referral = readReferral(json);

        return new InboundParseResult.Complete(List.of(NormalizedLead.builder()
                // traits is a TENANT-CONFIGURABLE attribute bag, not a fixed schema — name is an
                // attribute that happens to be in the sample, never a guarantee. Null is normal; the
                // pipeline falls back to the phone.
                .customerName(text(json, "data", "customer", "traits", "name"))
                // "917003705584" — E.164 digits, no leading '+', no "whatsapp:" scheme. Passed through
                // UNTOUCHED; PhoneCanonicalizer normalises exactly once, for everyone.
                .phoneRaw(phone)
                .phoneCountryHint(null)
                // WhatsApp carries no email, ever. Synthesizing one would collide on the second enquiry.
                .email(null)
                .message(text(json, "data", "message", "message"))
                .attribution(readAttribution(referral))
                // The payload PROVED an ad drove this. Anything else stays WHATSAPP = "source unknown".
                .sourceOverride(referral != null ? LeadSource.META_ADS : null)
                .travel(null)
                .extras(extras(json, referral))
                .build()));
    }

    /**
     * Meta's {@code referral}, if this BSP forwards it — probed, never assumed.
     *
     * <p>Returns null when absent, which is the expected outcome until a live Click-to-WhatsApp capture
     * proves otherwise. Null here is not an error and must never be treated as one: it simply means the
     * lead's true source is unknown, which is the honest majority case for a funnel.
     */
    private JsonNode readReferral(JsonNode json) {
        for (String[] path : REFERRAL_PATHS) {
            JsonNode node = at(json, path);
            if (node != null && node.isObject() && !node.isEmpty()) {
                return node;
            }
        }
        return null;
    }

    /**
     * The ad this lead came from.
     *
     * <p>Two traps, both verified against Meta's docs and both silent revenue bugs if missed:
     * <ul>
     *   <li><b>{@code source_id} is AD-grain only.</b> It is the Marketing API ad id — NOT a campaign or
     *       ad-set id. Reaching campaign grain needs a separate ad→adset→campaign lookup with ad-account
     *       credentials, which a webhook has no business doing. So {@code campaignId} stays null rather
     *       than being filled with an ad id, which would quietly corrupt every campaign report.</li>
     *   <li><b>{@code ctwa_clid} is OMITTED for WhatsApp Status placements.</b> A null click id therefore
     *       does NOT mean "not from an ad" — which is exactly why {@link #parse} gates the source
     *       override on the referral's PRESENCE and never on this field.</li>
     * </ul>
     *
     * <p>Everything is a String on purpose: {@code source_id} is an opaque 18-digit Meta id with no
     * guarantee it stays numeric, and {@code source_type} is an open vocabulary (current Cloud API
     * documents exactly one value, {@code "ad"}; the {@code ad|post} pair is sunset heritage still
     * mirrored by other BSPs). Neither may become an enum or a long.
     */
    private NormalizedLead.LeadAttributionData readAttribution(JsonNode referral) {
        if (referral == null) {
            return null;   // no evidence, no attribution row — never an invented one
        }
        return NormalizedLead.LeadAttributionData.builder()
                .adId(text(referral, "source_id"))
                // campaignId/adSetId deliberately absent — see the javadoc. Ad grain is all we are given.
                .campaignName(text(referral, "headline"))   // the ad's creative headline
                .placement(text(referral, "source_type"))
                .build();
    }

    /**
     * Diagnostics.
     *
     * <p>{@code ctwa_clid} rides here because it is the join key for reporting a booking back to Meta's
     * Conversions API later — throwing it away now would make that impossible, and it must NOT be hashed.
     * The ad's creative copy is kept separately from the customer's message on purpose: Meta's
     * {@code referral.body} is the AD's primary text and has nothing to do with
     * {@code messages[].text.body}, which is what the customer typed. Collapsing the two would put
     * marketing copy into a lead's notes as if the customer had written it.
     */
    private Map<String, String> extras(JsonNode json, JsonNode referral) {
        Map<String, String> out = new LinkedHashMap<>();
        put(out, "waMessageType", text(json, "data", "message", "message_content_type"));
        if (referral != null) {
            put(out, "ctwaClid", text(referral, "ctwa_clid"));
            put(out, "adSourceUrl", text(referral, "source_url"));
            put(out, "adHeadline", text(referral, "headline"));
            put(out, "adBody", text(referral, "body"));
            put(out, "adMediaType", text(referral, "media_type"));
            // welcome_message is a NESTED object with .text, not a flat string.
            put(out, "adWelcomeMessage", text(referral, "welcome_message", "text"));
        }
        return out;
    }

    private void put(Map<String, String> out, String key, String value) {
        if (value != null && out.size() < MAX_EXTRAS) {
            out.put(key, value);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private JsonNode at(JsonNode root, String... path) {
        JsonNode node = root;
        for (String segment : path) {
            if (node == null) return null;
            node = node.get(segment);
        }
        return node;
    }

    private String text(JsonNode root, String... path) {
        JsonNode node = at(root, path);
        if (node == null || node.isNull() || !node.isValueNode()) return null;
        String s = node.asText().trim();
        return s.isEmpty() ? null : s;
    }

    @Override
    public ChannelCatalogEntry catalog() {
        return ChannelCatalogEntry.builder()
                .displayName("WhatsApp (Interakt)")
                .description("Turn incoming WhatsApp messages into leads automatically — and see which "
                        + "ad sent them, when the message came from a Click-to-WhatsApp ad.")
                .credentialFields(List.of(new ChannelCatalogEntry.CredentialField(
                        // MUST be the framework's key — InboundVerifier reads exactly this for an HMAC.
                        InboundVerifier.SIGNING_SECRET_KEY,
                        "Webhook secret key",
                        true,
                        "Generate this in Interakt under Settings → Developer Settings, and paste it "
                                + "here. It is what proves a message really came from Interakt. This is "
                                + "NOT the same as your Interakt API key used for sending.")))
                .configFields(List.of())
                .setupHint("In Interakt go to Settings → Developer Settings, paste the URL below as your "
                        + "webhook URL, subscribe to the 'message_received' event, and copy the Secret "
                        + "Key into the box above. Then message your WhatsApp number from your phone — "
                        + "it should appear below within seconds.")
                .webhookUrlShown(true)
                .build();
    }
}
