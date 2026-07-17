package com.crm.travelcrm.leadsource.spi;

import lombok.Builder;

import java.util.Map;

/**
 * A lead as an adapter understood it — the boundary between "provider-specific bytes" and "our
 * domain".
 *
 * <p><b>The rule: no field here has a default, because a default baked into the SPI is a default
 * re-invented sixteen times.</b> Everything an adapter cannot legitimately know is absent, not
 * defaulted:
 *
 * <table>
 *   <caption>Deliberately absent</caption>
 *   <tr><td>{@code leadStage}</td>
 *       <td>The creation DTO has no server default, so sixteen authors each pick {@code NEW_LEAD}
 *           until one picks {@code CONTACTED}. The pipeline decides.</td></tr>
 *   <tr><td>{@code assignedUserId}</td>
 *       <td>A UUID publicId resolvable only against tenant users. An adapter parsing bytes cannot
 *           know them — {@code LeadAssignmentService.assignForInbound} decides.</td></tr>
 *   <tr><td>{@code tenantId}</td>
 *       <td>Resolved by the framework from the ingest token BEFORE the adapter runs. Putting it here
 *           re-opens the body-derived-tenant hole (owner decision 2).</td></tr>
 *   <tr><td>{@code leadSource}</td>
 *       <td>Derived from {@code channel.leadSource()}. Otherwise a JustDial adapter stamps
 *           {@code WEBSITE}. See {@link #sourceOverride} for the ONE narrow exception.</td></tr>
 *   <tr><td>{@code origin}</td>
 *       <td>Stamped {@code INTEGRATION} by the pipeline, always. This is what makes owner decision 4
 *           safe: {@code GOOGLE_ADS} from an adapter and {@code GOOGLE_ADS} typed by a human differ
 *           by {@code origin}, not by enum constant.</td></tr>
 * </table>
 *
 * @param customerName  as delivered; may be null when the provider withholds it until the lead is claimed
 * @param phoneRaw      <b>passed through untouched.</b> Adapters must NOT canonicalise — three phone
 *                      treatments already coexist in this codebase and disagree; sixteen adapters each
 *                      adding a fourth, differently, is the failure being prevented.
 *                      {@code PhoneCanonicalizer} is applied once, by the creation layer.
 * @param phoneCountryHint E.164 prefix ("+91") when the provider states the country; null otherwise.
 * @param email         <b>nullable on purpose</b>, even though the human form requires it. An IVR call
 *                      has no email, and an adapter synthesizing {@code noreply@ivr.local} has silently
 *                      made a UNIQUENESS decision that collides on the second call via
 *                      {@code uq_leads_email_tenant_open}.
 * @param message       the enquiry text, if any — becomes the lead's notes / first activity log
 * @param attribution   campaign-grain attribution (campaign name, ad id, gclid, …); null when the
 *                      channel carries none
 * @param travel        structured travel intent, when the channel's form actually asked for it; null
 *                      otherwise (most channels — a marketplace enquiry or a website form carries none)
 * @param sourceOverride <b>Almost always null. Read the rule before using it.</b>
 *
 *                      <p>The source normally comes from {@code channel.leadSource()}, and that rule
 *                      exists to stop sixteen adapters each nominating a source. This overrides it in
 *                      exactly ONE situation: <b>the payload itself PROVES a more specific origin than
 *                      the channel can</b>.
 *
 *                      <p>The case it was added for is WhatsApp, and it is the difference between a
 *                      real number and a useless one. WhatsApp is a FUNNEL, not a source — an Instagram
 *                      bio, a Google listing and a Click-to-WhatsApp ad all arrive on the same wire. When
 *                      Meta attaches its {@code referral} object, the lead demonstrably came from a
 *                      specific ad, and stamping it {@code WHATSAPP} would bury every rupee of Meta spend
 *                      in a bucket labelled with the medium it happened to travel over. Without the
 *                      override, "which channel should we fund?" becomes unanswerable.
 *
 *                      <p><b>The bar is EVIDENCE IN THE PAYLOAD, never inference.</b> Guessing a source
 *                      from message text, sender history or first-touch heuristics is forbidden: it
 *                      manufactures attribution numbers that real ad budget is then spent against. A lead
 *                      whose true source is unknown must stay visibly unknown — that is what the
 *                      channel's own default is FOR.</p>
 * @param extras        a <b>bounded diagnostic map</b>, documented as NEVER read by business logic, so a
 *                      provider quirk never forces a schema change. Known dumping-ground risk; the
 *                      mitigation is this contract plus review.
 */
@Builder
public record NormalizedLead(
        String customerName,
        String phoneRaw,
        String phoneCountryHint,
        String email,
        String message,
        LeadAttributionData attribution,
        TravelIntent travel,
        com.crm.travelcrm.lead.enums.LeadSource sourceOverride,
        Map<String, String> extras
) {

    public NormalizedLead {
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }

    /**
     * What the enquirer told the provider's form about the trip itself.
     *
     * <p>Exists because a Google Ads lead form asks these questions structurally
     * ({@code DEPARTURE_CITY}, {@code DEPARTURE_DATE}, {@code TRAVEL_BUDGET}, …), and letting that
     * collapse into free text would throw away the exact thing that makes a search lead convert better
     * than a social one — the desk would re-key what the customer already typed.
     *
     * <p><b>EVERY FIELD IS A STRING, AS DELIVERED. This is the important part.</b> The temptation is to
     * type these ({@code LocalDate departureDate}, {@code BigDecimal budget}) since {@code Lead} has them
     * typed — and it is a trap. Adapters are pure functions over bytes; they know the field NAMES the
     * provider documented, not the VALUE FORMATS, which are usually preset dropdown answers:
     * {@code TRAVEL_BUDGET} is far more likely to arrive as {@code "₹50,000 - ₹1,00,000"} than as
     * {@code 50000}, and {@code "3+"} is a plausible {@code NUMBER_OF_TRAVELERS}. A typed SPI would force
     * every adapter to guess a format, and a wrong guess writes a silently wrong budget onto a real lead
     * — worse than no budget, because nobody can see it is wrong.
     *
     * <p>So the SPI carries the provider's own words and {@code LeadIngestService} converts <b>only when
     * the value is unambiguous</b>, leaving anything it cannot read confidently in the lead's notes where
     * a human can act on it. Nothing is ever lost; it is only sometimes untyped.
     */
    @Builder
    public record TravelIntent(
            String departureCity,
            String departureCountry,
            /** No {@code Lead} column today — reaches the desk through the notes. */
            String destinationCity,
            String destinationCountry,
            String departureDate,
            String returnDate,
            String travellers,
            String budget,
            String accommodation
    ) {}

    /**
     * Campaign-grain attribution. Separate from the lead's own fields because it is written to its own
     * table: attribution is read by reporting, not by the lead screens, and every column here would
     * otherwise widen the row every lead query loads.
     */
    @Builder
    public record LeadAttributionData(
            String campaignName,
            String campaignId,
            String adId,
            String adSetId,
            String formId,
            String gclid,
            String keyword,
            String placement
    ) {}
}
