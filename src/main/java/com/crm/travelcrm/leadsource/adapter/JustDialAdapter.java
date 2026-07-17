package com.crm.travelcrm.leadsource.adapter;

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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JustDial — enquiries pushed to us when a buyer contacts the tenant's JustDial listing.
 *
 * <p><b>Second adapter, and the first against a THIRD-PARTY payload.</b> {@link WebsiteFormAdapter}
 * could ship without research because the payload is ours. This one cannot, and the honest statement of
 * where that leaves us is the most important thing in this file.
 *
 * <h2>THE FIELD NAMES BELOW ARE A GUESS. Read this before trusting them.</h2>
 *
 * <p>A dedicated research pass (28 agents, adversarial verification) established that <b>JustDial
 * publishes no API documentation, no developer portal, and no self-serve webhook UI.</b> That negative
 * is the one thing that WAS corroborated, by four-plus independent CRM vendors (Kylas, LeadSquared,
 * 3Sigma, 365CRM), all of which say the same thing: you generate a receiver URL and email it to your
 * JustDial account manager, who configures it server-side. <b>There is no self-serve path — onboarding
 * each tenant needs a human round-trip with JustDial.</b>
 *
 * <p>Everything else was REFUTED, and the reason is worth recording so nobody re-walks it: the widely
 * recited JustDial field list ({@code leadid}, {@code mobile}, {@code dncmobile}, {@code brancharea},
 * {@code branchpin}, {@code parentid}, …) traces back to a single domain that <b>does not exist</b> —
 * NXDOMAIN from independent resolvers, zero Wayback snapshots, ever — yet is echoed back with invented
 * example values by AI search summaries on demand. It has the exact profile of a hallucination that
 * acquired authority through repetition. Other "sources" for it are an SEO template page that returns a
 * byte-identical response for a nonsense app name, and listing SCRAPERS (inverse direction: they call
 * JustDial, not the reverse) whose schemas actively contradict it.
 *
 * <p>So: the names below are <b>plausible but unverified</b>. They may well be a faithful transcription
 * of a real payload — the DNC fields map to India's real TRAI/NCPR regime, which is not the kind of
 * detail one invents. But plausible is not verified, and this file must not be read as a contract.
 *
 * <p><b>Why shipping it provisionally is nonetheless defensible</b>, and not the "invented adapter" the
 * design doc warns against:
 * <ul>
 *   <li>The framework logs {@code lead_ingest_events.raw_payload} BEFORE parsing, so the first real
 *       delivery reveals the true field names in the tenant's deliveries panel whether we guessed right
 *       or wrong. A wrong guess is a visible {@code IGNORED}/{@code FAILED} row with the payload
 *       attached — not a silently lost enquiry. <b>This is the whole safety argument, and it only
 *       became true for a bodiless GET when {@code LeadIngestGateway.payloadToLog} was added; before
 *       that, a GET push logged an empty string and would have been undebuggable.</b></li>
 *   <li>Nothing hard-fails on an unknown shape: {@link #parse} returns {@code Ignored}, never throws,
 *       so a misread never becomes a retry storm that gets the notification URL disabled.</li>
 *   <li>Alias lists mean a near-miss on spelling still lands, and every shape is accepted
 *       ({@link #readRows}) rather than one being bet on.</li>
 *   <li>Nothing else in the system binds to these names — {@link NormalizedLead} is the boundary, so
 *       correcting them is a one-line edit here.</li>
 * </ul>
 *
 * <p><b>TO CONFIRM THE CONTRACT — the only thing that actually settles it:</b> capture one real delivery
 * (the Recent deliveries panel shows the payload verbatim) and reconcile the constants below against it.
 * Ten minutes, once a lead arrives. In the same email to the account manager, ask for: a sample payload,
 * the HTTP method, whether any signature/IP allowlist exists, and the exact semantics of the DNC flags.
 *
 * <p><b>DNC IS A LEGAL QUESTION, NOT AN ADAPTER'S.</b> If fields resembling {@code dncmobile} prove
 * real, they carry weight under India's DND regime and must GATE auto-dial and auto-WhatsApp follow-up,
 * not merely be persisted. This adapter deliberately only records them (see {@link #extras}); acting on
 * them is a product decision, and their polarity must be confirmed in writing — never guessed.
 *
 * <p>Pure, stateless, no database — see {@link LeadSourceAdapter}.
 */
@Component
public class JustDialAdapter implements LeadSourceAdapter {

    // ── Field aliases ────────────────────────────────────────────────────────
    //
    // PROVISIONAL — see the class javadoc. Each list is ordered most-likely-first. The first non-blank
    // wins, so adding a newly-observed spelling is an append, never a rewrite.

    /** JustDial's own id for the enquiry — the dedup key. Their push retries; without this a retry is a second lead. */
    private static final List<String> LEAD_ID_KEYS =
            List.of("leadid", "lead_id", "leadId", "jdleadid", "jd_lead_id", "id");

    private static final List<String> NAME_KEYS =
            List.of("name", "customername", "customer_name", "personname", "person_name", "fullname", "full_name");

    /** {@code mobile} first: JustDial's primary contact field is the mobile number, not a landline. */
    private static final List<String> PHONE_KEYS =
            List.of("mobile", "phone", "mobile_number", "mobilenumber", "phone_number", "phonenumber", "contact");

    /** A secondary/landline number, when the buyer left one as well. Becomes a note, never the lead's phone. */
    private static final List<String> ALT_PHONE_KEYS =
            List.of("phone", "landline", "alternate_mobile", "alternatemobile", "telephone");

    private static final List<String> EMAIL_KEYS =
            List.of("email", "emailid", "email_id", "emailaddress", "email_address");

    /**
     * Free-text requirement.
     *
     * <p>Listed, but expect it to be ABSENT: JustDial's push describes an enquiry structurally
     * (category + area) rather than carrying what the buyer typed. {@link #composeMessage} is what
     * covers that case.
     */
    private static final List<String> MESSAGE_KEYS =
            List.of("requirement", "message", "enquiry", "query", "comments", "remarks", "description");

    /** What the buyer was searching for, e.g. "Travel Agents". The closest thing to intent JustDial sends. */
    private static final List<String> CATEGORY_KEYS =
            List.of("category", "cat", "section", "categoryname", "category_name");

    private static final List<String> CITY_KEYS = List.of("city", "cityname", "city_name");
    private static final List<String> AREA_KEYS = List.of("area", "brancharea", "branch_area", "locality");
    private static final List<String> BRANCH_KEYS = List.of("branch", "branchname", "branch_name");
    private static final List<String> COMPANY_KEYS = List.of("company", "companyname", "company_name", "firm");
    private static final List<String> PINCODE_KEYS = List.of("pincode", "pin_code", "zip", "zipcode");

    /** JustDial's Do-Not-Call marker. Diagnostic only — see {@link #parse}. */
    private static final List<String> DNC_KEYS = List.of("dncmobile", "dnc_mobile", "dnc");

    /** Batch wrappers, in case a delivery carries several enquiries rather than one. */
    private static final List<String> BATCH_KEYS = List.of("leads", "data", "records", "items");

    /** Bounds {@link NormalizedLead#extras()} — it is a diagnostic map, not a dumping ground. */
    private static final int MAX_EXTRAS = 12;

    @Override
    public LeadSourceChannel channel() {
        return LeadSourceChannel.JUSTDIAL;
    }

    /**
     * The URL's ingest token is the whole authentication — <b>a deliberate statement, not an inherited
     * default.</b>
     *
     * <p>JustDial's lead push has no documented request signature: a vendor registers a notification URL
     * and JustDial POSTs to it. There is no shared secret to verify against, so whoever holds the URL can
     * post leads to it — which is exactly the {@link InboundVerification.TokenOnly} case the SPI
     * describes for marketplaces that "offer nothing better".
     *
     * <p><b>If a real delivery turns out to carry a key in the body</b> (the shape
     * {@link InboundVerification.SharedSecretInBody} exists for), switch to it here AND add the same JSON
     * path to {@link #secretFieldPaths()} — otherwise that live credential is persisted in plaintext next
     * to PII for the retention window. Do not add one speculatively: a verifier that checks a field
     * JustDial does not send fails CLOSED and drops every real lead.
     */
    @Override
    public InboundVerification verification() {
        return new InboundVerification.TokenOnly();
    }

    /**
     * A retry of a delivery we already handled must be dropped, not duplicated.
     *
     * <p>JustDial's own enquiry id is the only stable thing to key on. When it is absent we return
     * {@link IdempotencyKey#none()} rather than hashing the body: two genuine enquiries from the same
     * person for the same category would hash identically, and the framework would silently discard the
     * second — losing a real lead to protect against a duplicate.
     */
    @Override
    public IdempotencyKey dedupKey(RawInbound in) {
        for (Map<String, String> row : readRows(in)) {
            String id = firstNonBlank(row, LEAD_ID_KEYS);
            if (id != null) {
                return IdempotencyKey.of(id);
            }
        }
        return IdempotencyKey.none();
    }

    @Override
    public InboundParseResult parse(RawInbound in) {
        List<Map<String, String>> rows = readRows(in);

        List<NormalizedLead> leads = new ArrayList<>();
        for (Map<String, String> row : rows) {
            NormalizedLead lead = toLead(row);
            if (lead != null) {
                leads.add(lead);
            }
        }

        if (leads.isEmpty()) {
            // A verification ping, a health check, or a shape we did not understand. Ignored rather than
            // thrown: throwing turns routine traffic into a provider retry storm, and JustDial reacts to
            // repeated errors by disabling the notification URL — which loses every future lead.
            // The raw body is already persisted, so a misread shape is visible and fixable.
            return new InboundParseResult.Ignored("no_contact_details");
        }
        return new InboundParseResult.Complete(leads);
    }

    /** @return null when the row carries no way to contact anyone — i.e. it is not a lead */
    private NormalizedLead toLead(Map<String, String> row) {
        String phone = firstNonBlank(row, PHONE_KEYS);
        String email = firstNonBlank(row, EMAIL_KEYS);

        if (phone == null && email == null) {
            return null;
        }

        return NormalizedLead.builder()
                .customerName(firstNonBlank(row, NAME_KEYS))
                // Phone passes through UNTOUCHED. The framework canonicalises exactly once; an adapter
                // doing it here would be the fourth phone treatment in this codebase.
                .phoneRaw(phone)
                // JustDial is an India-only marketplace, so a bare 10-digit number is unambiguously +91.
                // A HINT, not a rewrite — PhoneCanonicalizer still decides.
                .phoneCountryHint("+91")
                .email(email)
                .message(composeMessage(row))
                // No attribution: JustDial is a marketplace listing, not a campaign. Inventing a
                // campaign name here would put fiction in the attribution reports.
                .attribution(null)
                .extras(extras(row))
                .build();
    }

    /**
     * What the sales desk reads first.
     *
     * <p>JustDial typically sends no free-text requirement — it says "someone searched {@code Travel
     * Agents} in {@code Pune}". A lead whose notes are empty is one the desk has to open JustDial to
     * understand, so when there is no message we compose one from the structural fields.
     *
     * <p>This is presentation, not protocol: every value used is echoed verbatim in {@link #extras}, and
     * nothing downstream parses this string.
     */
    private String composeMessage(Map<String, String> row) {
        String stated = firstNonBlank(row, MESSAGE_KEYS);
        if (stated != null) {
            return stated;
        }

        String category = firstNonBlank(row, CATEGORY_KEYS);
        String area = firstNonBlank(row, AREA_KEYS);
        String city = firstNonBlank(row, CITY_KEYS);

        if (category == null && area == null && city == null) {
            return null;      // nothing to say — better than a hollow "JustDial enquiry"
        }

        StringBuilder sb = new StringBuilder("JustDial enquiry");
        if (category != null) {
            sb.append(" for ").append(category);
        }
        String where = area != null && city != null ? area + ", " + city : area != null ? area : city;
        if (where != null) {
            sb.append(" in ").append(where);
        }
        return sb.append('.').toString();
    }

    /**
     * Diagnostics only — documented as never read by business logic, which is what keeps a provider quirk
     * from becoming a schema change.
     *
     * <p>{@code dncmobile} rides along here deliberately: it is a compliance-relevant flag we do not yet
     * act on, and dropping it would destroy the evidence. Acting on it is a product decision, not an
     * adapter's.
     */
    private Map<String, String> extras(Map<String, String> row) {
        Map<String, String> out = new LinkedHashMap<>();
        put(out, "jdLeadId", firstNonBlank(row, LEAD_ID_KEYS));
        put(out, "category", firstNonBlank(row, CATEGORY_KEYS));
        put(out, "city", firstNonBlank(row, CITY_KEYS));
        put(out, "area", firstNonBlank(row, AREA_KEYS));
        put(out, "branch", firstNonBlank(row, BRANCH_KEYS));
        put(out, "company", firstNonBlank(row, COMPANY_KEYS));
        put(out, "pincode", firstNonBlank(row, PINCODE_KEYS));
        put(out, "dncMobile", firstNonBlank(row, DNC_KEYS));

        String alt = firstNonBlank(row, ALT_PHONE_KEYS);
        String primary = firstNonBlank(row, PHONE_KEYS);
        if (alt != null && !alt.equals(primary)) {
            put(out, "alternatePhone", alt);
        }
        return out;
    }

    private void put(Map<String, String> out, String key, String value) {
        if (value != null && out.size() < MAX_EXTRAS) {
            out.put(key, value);
        }
    }

    // ── Body shapes ──────────────────────────────────────────────────────────

    /**
     * Every enquiry in this delivery, as flat string maps.
     *
     * <p><b>Four shapes are accepted, because which one JustDial actually sends is the single thing we
     * could not establish.</b> Rather than bet the channel on one guess, we read whichever is present —
     * they are mutually exclusive in practice, so accepting all four costs nothing and removes an entire
     * category of "connected, receiving nothing" failure:
     *
     * <ol>
     *   <li><b>GET query string</b> — {@code ?name=…&mobile=…}. Listed FIRST among the fallbacks
     *       deliberately: a bodiless GET push is the most credible hypothesis for JustDial, and an
     *       adapter that only read bodies would parse every real lead as zero leads while reporting a
     *       perfectly healthy connection.</li>
     *   <li>JSON object — one enquiry.</li>
     *   <li>JSON array, or a {@code {"leads":[…]}} wrapper — a batch. This is why
     *       {@link InboundParseResult.Complete} takes a list.</li>
     *   <li>Form-encoded body.</li>
     * </ol>
     *
     * <p>Keys are lower-cased so the alias lists need not enumerate {@code leadId} / {@code leadid} /
     * {@code LEADID} separately.
     */
    private List<Map<String, String>> readRows(RawInbound in) {
        JsonNode json = in.json();

        if (json != null) {
            if (json.isArray()) {
                return flatten(json);
            }
            if (json.isObject()) {
                for (String key : BATCH_KEYS) {
                    JsonNode batch = json.get(key);
                    if (batch != null && batch.isArray()) {
                        return flatten(batch);
                    }
                }
                Map<String, String> flat = flattenObject(json);
                if (!flat.isEmpty()) {
                    return List.of(flat);
                }
            }
        }

        Map<String, String> form = lowerKeys(in.form());
        if (!form.isEmpty()) {
            return List.of(form);
        }

        // No body at all — a GET push carries the lead in the query string.
        Map<String, String> query = lowerKeys(in.queryAll());
        return query.isEmpty() ? List.of() : List.of(query);
    }

    private List<Map<String, String>> flatten(JsonNode array) {
        List<Map<String, String>> out = new ArrayList<>();
        for (JsonNode node : array) {
            if (node != null && node.isObject()) {
                Map<String, String> flat = flattenObject(node);
                if (!flat.isEmpty()) {
                    out.add(flat);
                }
            }
        }
        return out;
    }

    /** Value nodes only, one level deep — a nested object is not a shape we can honestly claim to know. */
    private Map<String, String> flattenObject(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            if (e.getValue() != null && e.getValue().isValueNode()) {
                out.put(e.getKey().toLowerCase(java.util.Locale.ROOT), e.getValue().asText());
            }
        });
        return out;
    }

    private Map<String, String> lowerKeys(Map<String, String> in) {
        Map<String, String> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k.toLowerCase(java.util.Locale.ROOT), v));
        return out;
    }

    private String firstNonBlank(Map<String, String> row, List<String> keys) {
        for (String key : keys) {
            String v = row.get(key);
            if (v != null && !v.isBlank() && !"null".equalsIgnoreCase(v.trim())) {
                return v.trim();
            }
        }
        return null;
    }

    @Override
    public ChannelCatalogEntry catalog() {
        return ChannelCatalogEntry.builder()
                .displayName("JustDial")
                .description("Receive enquiries from your JustDial listing the moment a buyer contacts you.")
                // Nothing to authenticate with: JustDial posts to the URL you register with them, and the
                // token in that URL is the whole boundary. See verification().
                .credentialFields(List.of())
                .configFields(List.of())
                .setupHint("Copy the URL below and give it to JustDial as your lead notification / "
                        + "push URL — through your JD seller account or your JustDial account manager. "
                        + "Enquiries then arrive here automatically. Send a test enquiry once it is set "
                        + "up: the Recent deliveries panel shows exactly what JustDial sent.")
                .webhookUrlShown(true)
                .build();
    }

    /**
     * Nothing to redact: {@link #verification()} declares {@link InboundVerification.TokenOnly}, so no
     * credential rides in the body. <b>This must change in the same edit if that ever does.</b>
     */
    @Override
    public java.util.Set<String> secretFieldPaths() {
        return java.util.Set.of();
    }
}
