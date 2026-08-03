package com.crm.travelcrm.customer.dto.response;

import com.crm.travelcrm.customer.enums.CustomerType;
import com.crm.travelcrm.customer.enums.LoyaltyTier;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The answer to "does a customer already exist with this phone or email?".
 *
 * <p>Returned in two places, and deliberately the SAME shape in both so the popup can never
 * disagree with what was actually saved:
 * <ul>
 *   <li>{@code GET /api/customers/lookup?phone=&email=} — the pre-submit probe. The lead form calls
 *       it while the clerk types and shows the "Customer found" popup from the result.</li>
 *   <li>Nested as {@code customerMatch} on {@code LeadResponseDto} — the authoritative, create-time
 *       answer. The probe is advisory (someone else may add the customer a second later); this one
 *       reflects the link that was actually written.</li>
 * </ul>
 *
 * <p>A no-match is a normal answer, not an error: {@code matched=false} with every other field null.
 * The endpoint answers 200 in that case — a 404 would make the frontend treat "new customer", the
 * commonest outcome, as a failure.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CustomerMatchResponse {

    /** False means no existing customer carried this phone or email. Everything below is then null. */
    boolean matched;

    /**
     * Which identifier hit — {@code PHONE}, {@code EMAIL}, or {@code BOTH}. Drives the wording of
     * the popup ("Customer found with this phone number" vs "...with this email address"), which is
     * why it is an explicit field rather than something the UI re-derives by comparing strings.
     */
    MatchedOn matchedOn;

    /** Ready-to-display sentence, so every caller phrases the popup identically. */
    String message;

    // ── The matched customer (a trimmed profile, not the full record) ─────────

    /** publicId — the id every customer endpoint is keyed by. */
    UUID customerId;

    /** Human-friendly code, e.g. {@code CUS10001}. */
    String customerCode;

    String name;
    String phone;
    String email;
    String city;
    String state;

    LocalDate birthday;
    LocalDate anniversary;

    CustomerType type;
    LoyaltyTier tier;

    // ── Relationship context — what makes the popup worth showing ─────────────
    // A clerk deciding whether to reuse this customer wants to know if they are a repeat client, not
    // just that a row exists. Computed live from the bookings table, never stored on Customer.

    /** Lifetime count of active bookings. */
    long totalBookings;

    /** Lifetime spend across active bookings. */
    BigDecimal totalSpent;

    /** Travel date of the most recent booking, or null if none. */
    LocalDate lastBookingDate;

    /**
     * Names of the lead fields the server actually filled in from this customer. Populated only on
     * the create-time response; null on the lookup probe, which writes nothing.
     *
     * <p>Exists so the popup can say precisely what changed instead of implying the whole form was
     * rewritten — prefill only ever fills fields the clerk left blank.
     */
    List<String> prefilledFields;

    public enum MatchedOn { PHONE, EMAIL, BOTH }

    /** The no-match answer. A singleton-shaped helper so no caller invents a different empty form. */
    public static CustomerMatchResponse noMatch() {
        return CustomerMatchResponse.builder().matched(false).build();
    }
}
