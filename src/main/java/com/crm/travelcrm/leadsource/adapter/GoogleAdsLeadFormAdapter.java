package com.crm.travelcrm.leadsource.adapter;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Google Ads Lead Form Extensions — the lead a searcher submits without leaving the results page.
 *
 * <p><b>The opposite of {@link JustDialAdapter}, and worth stating plainly.</b> Google publishes a real,
 * versioned developer contract with a verbatim sample payload
 * (<a href="https://developers.google.com/google-ads/webhook/docs/implementation">webhook docs</a>), so
 * <b>nothing here is a guess</b>. Field names, the auth mechanism, the column vocabulary and the required
 * response codes are all documented. This is what an adapter looks like when the provider does its job.
 *
 * <h3>Three documented behaviours this adapter exists to honour</h3>
 * <ul>
 *   <li><b>{@code google_key} is the authentication</b>: the advertiser types a key into the Google Ads
 *       UI and Google echoes it in every payload — literally the
 *       {@link InboundVerification.SharedSecretInBody} case. It is stored under
 *       {@link InboundVerifier#SHARED_SECRET_KEY} and compared constant-time by the framework.</li>
 *   <li><b>{@code lead_id} deduplicates</b>: Google states "a single lead is not guaranteed to be
 *       delivered exactly once" and retries on 5xx. Without {@link #dedupKey} a retry is a second lead.</li>
 *   <li><b>Never parse strictly.</b> Google's own warning: "Don't write code that expects a fixed set of
 *       fields or that would fail if new, unexpected fields are present." Hence {@link #parse} reads only
 *       what it needs and passes everything else through as text rather than rejecting it.</li>
 * </ul>
 *
 * <h3>Why this channel matters more than its volume suggests</h3>
 * Google's column vocabulary is vertical-aware and includes a full TRAVEL set — {@code DESTINATION_CITY},
 * {@code DEPARTURE_CITY}, {@code DEPARTURE_DATE}, {@code RETURN_DATE}, {@code NUMBER_OF_TRAVELERS},
 * {@code TRAVEL_BUDGET}. A search lead therefore arrives with its intent already structured, which is the
 * whole reason its conversion rate beats a social lead.
 *
 * <p><b>Known limitation, deliberate for now:</b> {@link NormalizedLead} has no travel fields, so those
 * answers land in the lead's message (readable by the desk, verbatim) and in {@link #extras} — NOT on
 * {@code Lead.travelDate} / {@code budget} / {@code departCity}, which do exist as real columns. Landing
 * them structurally needs a {@code NormalizedLead} extension, which is an SPI change and its own review.
 * Composing them into the message loses nothing visible; it only costs the desk a re-key.
 */
@Component
public class GoogleAdsLeadFormAdapter implements LeadSourceAdapter {

    /** Documented top-level fields. Not a guess — see the class javadoc. */
    private static final String F_LEAD_ID     = "lead_id";
    private static final String F_IS_TEST     = "is_test";
    private static final String F_COLUMNS     = "user_column_data";
    private static final String F_GOOGLE_KEY  = "google_key";
    private static final String F_CAMPAIGN_ID = "campaign_id";
    private static final String F_ADGROUP_ID  = "adgroup_id";
    private static final String F_CREATIVE_ID = "creative_id";
    private static final String F_FORM_ID     = "form_id";
    private static final String F_GCL_ID      = "gcl_id";

    // Documented column_id values. Ordered most-specific-first where several can carry one concept.
    private static final List<String> EMAIL_COLUMNS = List.of("EMAIL", "WORK_EMAIL");
    private static final List<String> PHONE_COLUMNS =
            List.of("PHONE_NUMBER", "PHONE_NUMBER_VERIFIED", "WORK_PHONE");

    /**
     * Columns consumed into dedicated {@link NormalizedLead} fields. Everything NOT in here becomes a
     * message line — which is what keeps a custom question from being silently dropped.
     */
    private static final Set<String> CONSUMED_COLUMNS =
            Set.of("FULL_NAME", "FIRST_NAME", "LAST_NAME", "EMAIL", "WORK_EMAIL",
                    "PHONE_NUMBER", "PHONE_NUMBER_VERIFIED", "WORK_PHONE");

    /** Travel columns mirrored into {@code extras} as stable keys, for whenever the SPI grows them. */
    private static final Map<String, String> TRAVEL_EXTRAS = Map.of(
            "DESTINATION_CITY", "destinationCity",
            "DESTINATION_COUNTRY", "destinationCountry",
            "DEPARTURE_CITY", "departureCity",
            "DEPARTURE_COUNTRY", "departureCountry",
            "DEPARTURE_DATE", "departureDate",
            "RETURN_DATE", "returnDate",
            "NUMBER_OF_TRAVELERS", "travellers",
            "TRAVEL_BUDGET", "budget",
            "TRAVEL_ACCOMMODATION", "accommodation");

    /** Keeps {@code extras} a bounded diagnostic map rather than a dumping ground. */
    private static final int MAX_EXTRAS = 12;

    @Override
    public LeadSourceChannel channel() {
        return LeadSourceChannel.GOOGLE_ADS;
    }

    /**
     * The advertiser's own key, echoed by Google in the body.
     *
     * <p>Google frames validating it as a confidence measure rather than a hard requirement; this
     * framework treats it as mandatory, because the alternative is an unauthenticated lead-creating
     * endpoint whose only protection is a URL that leaks through access logs and Referer headers. The
     * framework fails CLOSED when the credential is absent — the connection cannot be saved without it
     * (see {@link #catalog}), so that path is unreachable rather than merely unlikely.
     */
    @Override
    public InboundVerification verification() {
        return new InboundVerification.SharedSecretInBody(F_GOOGLE_KEY);
    }

    /**
     * <b>Mandatory, and directly coupled to {@link #verification()}.</b> {@code google_key} is a LIVE
     * credential travelling in the body; without this it would be written to
     * {@code lead_ingest_events.raw_payload} in plaintext, next to PII, for the entire retention window —
     * and Google's own advice is to keep it confidential and rotate it if it leaks.
     */
    @Override
    public Set<String> secretFieldPaths() {
        return Set.of(F_GOOGLE_KEY);
    }

    /**
     * Google retries on 5xx and explicitly does not guarantee exactly-once delivery, so {@code lead_id}
     * is the difference between one lead and several.
     */
    @Override
    public IdempotencyKey dedupKey(RawInbound in) {
        JsonNode json = in.json();
        return json == null ? IdempotencyKey.none() : IdempotencyKey.of(text(json, F_LEAD_ID));
    }

    @Override
    public InboundParseResult parse(RawInbound in) {
        JsonNode json = in.json();
        if (json == null || !json.isObject()) {
            return new InboundParseResult.Ignored("unparseable_body");
        }

        // Google's "Send test lead" button sets this. Creating a real lead from it would put fiction in
        // the pipeline AND consume the tenant's lead quota. Ignored — not dropped: the delivery is still
        // logged, so clicking the test button PROVES the wiring works without polluting anything.
        if (json.path(F_IS_TEST).asBoolean(false)) {
            return new InboundParseResult.Ignored("test_lead");
        }

        Map<String, String> columns = readColumns(json);

        String phone = firstPresent(columns, PHONE_COLUMNS);
        String email = firstPresent(columns, EMAIL_COLUMNS);
        if (phone == null && email == null) {
            return new InboundParseResult.Ignored("no_contact_details");
        }

        return new InboundParseResult.Complete(List.of(NormalizedLead.builder()
                .customerName(readName(columns))
                // Untouched: the framework canonicalises exactly once.
                .phoneRaw(phone)
                // NO country hint, unlike JustDial: Google Ads is global, so the country is genuinely
                // unknown here. Inventing "+91" would silently mangle an overseas enquiry.
                .phoneCountryHint(null)
                .email(email)
                .message(composeMessage(json, columns))
                .attribution(readAttribution(json))
                .travel(readTravelIntent(columns))
                .extras(extras(json, columns))
                .build()));
    }

    /**
     * {@code column_id → string_value}.
     *
     * <p>Unknown ids are kept, not rejected: Google warns that new optional fields appear over time, and
     * an unknown id is usually a custom question the advertiser added — the most valuable answer on the
     * form.
     */
    private Map<String, String> readColumns(JsonNode json) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonNode array = json.get(F_COLUMNS);
        if (array == null || !array.isArray()) {
            return out;
        }
        for (JsonNode column : array) {
            String id = text(column, "column_id");
            String value = text(column, "string_value");
            if (id != null && value != null) {
                out.put(id.toUpperCase(Locale.ROOT), value);
            }
        }
        return out;
    }

    /** {@code FULL_NAME} if given, else the parts — Google sends one shape or the other, never both. */
    private String readName(Map<String, String> columns) {
        String full = columns.get("FULL_NAME");
        if (full != null) {
            return full;
        }
        String first = columns.get("FIRST_NAME");
        String last = columns.get("LAST_NAME");
        if (first == null && last == null) {
            return null;
        }
        return first == null ? last : last == null ? first : first + " " + last;
    }

    /**
     * Every answer the lead fields do not already carry, as {@code Label: value} lines.
     *
     * <p><b>Driven by {@code column_name}, not {@code column_id}</b>, and that is the point: for a custom
     * question Google puts the QUESTION TEXT in {@code column_name}. So "Which month are you planning?"
     * arrives readable, with its answer, instead of being dropped for not matching a known id. This is
     * also how the travel columns reach the desk today — see the class javadoc's known limitation.
     */
    private String composeMessage(JsonNode json, Map<String, String> columns) {
        JsonNode array = json.get(F_COLUMNS);
        if (array == null || !array.isArray()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (JsonNode column : array) {
            String id = upper(text(column, "column_id"));
            String value = text(column, "string_value");
            if (id == null || value == null || CONSUMED_COLUMNS.contains(id)) {
                continue;
            }
            String label = text(column, "column_name");
            if (label == null || label.isBlank()) {
                label = prettify(id);
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(label).append(": ").append(value);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * The trip, as the searcher described it on Google's own form.
     *
     * <p><b>Values are passed through verbatim — no parsing, and that is the contract, not laziness.</b>
     * An adapter is a pure function over bytes: it knows Google DOCUMENTS a {@code TRAVEL_BUDGET} column,
     * but not whether this advertiser's form offers a free number or the dropdown buckets Google's
     * vertical questions usually are. {@code LeadIngestService} converts only what is unambiguous and
     * leaves the rest in the notes — see {@link NormalizedLead.TravelIntent}.
     *
     * @return null when the form asked nothing about the trip, so a plain contact form does not carry an
     *         empty travel record around
     */
    private NormalizedLead.TravelIntent readTravelIntent(Map<String, String> columns) {
        NormalizedLead.TravelIntent travel = NormalizedLead.TravelIntent.builder()
                .departureCity(columns.get("DEPARTURE_CITY"))
                .departureCountry(columns.get("DEPARTURE_COUNTRY"))
                .destinationCity(columns.get("DESTINATION_CITY"))
                .destinationCountry(columns.get("DESTINATION_COUNTRY"))
                .departureDate(columns.get("DEPARTURE_DATE"))
                .returnDate(columns.get("RETURN_DATE"))
                .travellers(columns.get("NUMBER_OF_TRAVELERS"))
                .budget(columns.get("TRAVEL_BUDGET"))
                .accommodation(columns.get("TRAVEL_ACCOMMODATION"))
                .build();

        return TRAVEL_EXTRAS.keySet().stream().anyMatch(columns::containsKey) ? travel : null;
    }

    /**
     * Campaign-grain attribution — the reason this channel can be held to a ROI number at all.
     *
     * <p>{@code gcl_id} is the click id that ties this lead back to the exact paid click, which is what
     * makes cost-per-lead computable rather than estimated.
     */
    private NormalizedLead.LeadAttributionData readAttribution(JsonNode json) {
        return NormalizedLead.LeadAttributionData.builder()
                .campaignId(idOrNull(json, F_CAMPAIGN_ID))
                .adSetId(idOrNull(json, F_ADGROUP_ID))     // Google's ad group is the ad-set grain
                .adId(idOrNull(json, F_CREATIVE_ID))
                .formId(idOrNull(json, F_FORM_ID))
                .gclid(text(json, F_GCL_ID))
                .build();
    }

    /** Structured travel answers, kept machine-readable for whenever they earn real Lead columns. */
    private Map<String, String> extras(JsonNode json, Map<String, String> columns) {
        Map<String, String> out = new LinkedHashMap<>();
        TRAVEL_EXTRAS.forEach((columnId, extraKey) -> {
            String value = columns.get(columnId);
            if (value != null && out.size() < MAX_EXTRAS) {
                out.put(extraKey, value);
            }
        });
        String apiVersion = text(json, "api_version");
        if (apiVersion != null && out.size() < MAX_EXTRAS) {
            out.put("apiVersion", apiVersion);   // the contract can version; record which one arrived
        }
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String firstPresent(Map<String, String> columns, List<String> ids) {
        for (String id : ids) {
            String v = columns.get(id);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    /** Google's sample sends {@code "adgroup_id":0} — a zero here means "absent", not ad group zero. */
    private String idOrNull(JsonNode json, String field) {
        String raw = text(json, field);
        return raw == null || "0".equals(raw) ? null : raw;
    }

    private String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) return null;
        String s = value.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    /** {@code NUMBER_OF_TRAVELERS} → {@code "Number of travelers"} — only when Google sends no label. */
    private String prettify(String columnId) {
        String spaced = columnId.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    @Override
    public ChannelCatalogEntry catalog() {
        return ChannelCatalogEntry.builder()
                .displayName("Google Ads Lead Forms")
                .description("Receive lead-form submissions from your Google Search campaigns, with the "
                        + "click id that ties each lead back to its ad spend.")
                .credentialFields(List.of(new ChannelCatalogEntry.CredentialField(
                        // MUST be the framework's key — InboundVerifier reads exactly this.
                        InboundVerifier.SHARED_SECRET_KEY,
                        "Webhook key",
                        true,
                        "Type any hard-to-guess value here AND in the same 'Webhook key' box in Google "
                                + "Ads. Google sends it back with every lead so we can prove the lead "
                                + "really came from Google. Keep it secret; change it here and in "
                                + "Google Ads if it ever leaks.")))
                .configFields(List.of())
                .setupHint("In Google Ads open your lead form asset → Lead delivery option → Webhook. "
                        + "Paste the URL below as the Webhook URL, set the Webhook key to the same value "
                        + "you entered here, then press 'Send test data'. A successful test shows up "
                        + "below as a delivery marked 'test_lead' — it proves the wiring without "
                        + "creating a real lead.")
                .webhookUrlShown(true)
                .build();
    }
}
