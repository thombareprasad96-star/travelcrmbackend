package com.crm.travelcrm.customer.service;

import com.crm.travelcrm.booking.dto.CustomerBookingMetrics;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.common.util.PhoneCanonicalizer;
import com.crm.travelcrm.common.util.PhoneNormalizer;
import com.crm.travelcrm.customer.dto.response.CustomerMatchResponse;
import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.customer.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Answers one question, in one place: <b>does this tenant already have a customer with this phone
 * or this email?</b>
 *
 * <p>Both callers of this class must agree or the product lies to the user:
 * <ul>
 *   <li>{@code GET /api/customers/lookup} — the advisory probe the lead form runs while the clerk
 *       types, which raises the "Customer found" popup.</li>
 *   <li>{@code LeadServiceImpl.createLead} — the authoritative match, which links the lead and
 *       prefills it.</li>
 * </ul>
 * If those two ran different logic, the popup could promise a link that creation then did not make.
 * That is why the matching lives here and neither caller re-implements it.
 *
 * <p><b>Why phone matching goes through the canonical shadow key.</b> {@code Customer.phone} holds
 * the raw string somebody typed, and the tenant-uniqueness index is keyed on it, so it cannot be
 * rewritten. But raw strings do not match across formats — a lead phoned in as {@code +919812345678}
 * would not find the customer saved as {@code +91 98765 43210}. Matching on
 * {@code phone_normalized} (E.164, derived by {@link PhoneCanonicalizer}) is what makes this
 * reliable instead of format-dependent. A raw-equality lookup runs as a fallback for rows written
 * before the shadow column existed and not yet backfilled.
 *
 * <p><b>A null canonical is never a match.</b> {@link PhoneCanonicalizer} deliberately returns null
 * rather than guess at a number it cannot parse. Matching null against null would collapse every
 * unparseable phone in the tenant onto a single identity — the worst possible failure for this
 * feature, since it would attach a stranger's enquiry to someone else's customer record.
 */
@Component
@RequiredArgsConstructor
@Slf4j   // SLF4J API, bridged to Log4j2 — the convention in 126 of this codebase's classes
public class CustomerMatcher {

    private final CustomerRepository customerRepository;
    private final BookingRepository bookingRepository;

    /** Country assumed for phones typed without a prefix. Same property the lead module reads. */
    @Value("${app.lead.default-country-code:+91}")
    private String defaultCountryCode;

    /**
     * The matched customer plus which identifier found them. A record rather than a bare
     * {@code Optional<Customer>} because the caller needs the {@code matchedOn} to word the popup,
     * and re-deriving it by comparing strings afterwards is how the two drift apart.
     */
    public record Match(Customer customer, CustomerMatchResponse.MatchedOn matchedOn) {
    }

    /** The canonical form this deployment derives for a phone. Exposed so writers stamp it the same way. */
    public String canonicalPhone(String rawPhone) {
        return PhoneCanonicalizer.canonical(rawPhone, defaultCountryCode);
    }

    /**
     * Find the existing customer for this phone or email, within the tenant.
     *
     * <p><b>Phone wins.</b> It is the per-tenant natural key — the thing
     * {@code uq_customers_phone_tenant} enforces and the thing conversion resolves on — whereas
     * email is nullable, shareable (one family mailbox, one office address) and not unique. When
     * phone and email point at two <em>different</em> customers, the phone's customer is returned:
     * picking the email's would link the lead to someone whose phone number it does not have, and
     * the booking conversion would then immediately disagree.
     *
     * @param rawPhone phone as typed; may carry spaces, dashes or a leading zero
     * @param rawEmail email as typed; case-insensitive
     */
    public Optional<Match> find(String rawPhone, String rawEmail, Long tenantId) {
        if (tenantId == null) return Optional.empty();

        Optional<Customer> byPhone = findByPhone(rawPhone, tenantId);
        Optional<Customer> byEmail = findByEmail(rawEmail, tenantId);

        if (byPhone.isPresent()) {
            Customer customer = byPhone.get();
            // `id` is a long primitive on BaseEntity, so this is == and not equals().
            boolean emailAgrees = byEmail.isPresent()
                    && byEmail.get().getId() == customer.getId();
            if (byEmail.isPresent() && !emailAgrees) {
                // Not an error — two real customers, one holding the phone and another the email.
                // Logged because it usually means a merge is overdue, and because a clerk seeing the
                // phone's customer in the popup while having typed the other one's email deserves an
                // explanation to exist somewhere.
                log.info("Lead contact matches two different customers in tenant {} — "
                                + "phone -> {}, email -> {}. Linking on phone (the natural key).",
                        tenantId, customer.getCustomerCode(), byEmail.get().getCustomerCode());
            }
            return Optional.of(new Match(customer,
                    emailAgrees ? CustomerMatchResponse.MatchedOn.BOTH
                                : CustomerMatchResponse.MatchedOn.PHONE));
        }

        return byEmail.map(c -> new Match(c, CustomerMatchResponse.MatchedOn.EMAIL));
    }

    private Optional<Customer> findByPhone(String rawPhone, Long tenantId) {
        if (PhoneNormalizer.isBlank(rawPhone)) return Optional.empty();

        String canonical = canonicalPhone(rawPhone);
        if (canonical != null) {
            Optional<Customer> hit = customerRepository
                    .findFirstByPhoneNormalizedAndTenantIdAndDeletedAtIsNullOrderByIdAsc(canonical, tenantId);
            if (hit.isPresent()) return hit;
        }

        // Fallback: exact raw match, for rows written before phone_normalized existed on customers
        // and not covered by the backfill (an un-canonicalisable number keeps a null shadow value).
        return customerRepository.findByPhoneAndTenantIdAndDeletedAtIsNull(
                PhoneNormalizer.normalize(rawPhone), tenantId);
    }

    private Optional<Customer> findByEmail(String rawEmail, Long tenantId) {
        if (rawEmail == null || rawEmail.isBlank()) return Optional.empty();
        return customerRepository.findFirstByEmailIgnoreCaseAndTenantIdAndDeletedAtIsNullOrderByIdAsc(
                rawEmail.trim().toLowerCase(), tenantId);
    }

    // ── Response assembly ────────────────────────────────────────────────────

    /**
     * Build the popup payload for a match, enriched with the relationship context that makes it
     * worth showing (repeat customer? how much have they spent?).
     *
     * @param prefilledFields lead fields actually written from this customer; null on the read-only
     *                        probe, which changes nothing
     */
    public CustomerMatchResponse toResponse(Match match, Long tenantId, List<String> prefilledFields) {
        Customer customer = match.customer();
        CustomerBookingMetrics metrics = bookingRepository
                .findMetricsByCustomerIds(tenantId, List.of(customer.getId()))
                .stream().findFirst().orElse(null);

        return CustomerMatchResponse.builder()
                .matched(true)
                .matchedOn(match.matchedOn())
                .message(messageFor(match.matchedOn()))
                .customerId(customer.getPublicId())
                .customerCode(customer.getCustomerCode())
                .name(customer.getName())
                .phone(customer.getPhone())
                .email(customer.getEmail())
                .city(customer.getCity())
                .state(customer.getState())
                .birthday(customer.getBirthday())
                .anniversary(customer.getAnniversary())
                .type(customer.getType())
                .tier(customer.getTier())
                .totalBookings(metrics != null ? metrics.getBookingCount() : 0L)
                .totalSpent(metrics != null && metrics.getTotalSpent() != null
                        ? metrics.getTotalSpent() : BigDecimal.ZERO)
                .lastBookingDate(metrics != null ? metrics.getLastBookingDate() : null)
                .prefilledFields(prefilledFields == null || prefilledFields.isEmpty()
                        ? null : List.copyOf(prefilledFields))
                .build();
    }

    private String messageFor(CustomerMatchResponse.MatchedOn matchedOn) {
        return switch (matchedOn) {
            case PHONE -> "Customer found with this phone number";
            case EMAIL -> "Customer found with this email address";
            case BOTH  -> "Customer found with this email / phone";
        };
    }
}
