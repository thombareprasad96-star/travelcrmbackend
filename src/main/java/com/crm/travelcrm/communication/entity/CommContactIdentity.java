package com.crm.travelcrm.communication.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.communication.enums.ContactIdentityType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The canonical address of one external party — the key every customer conversation hangs off.
 *
 * <p><b>Why this exists instead of hanging conversations off Customer or Lead.</b> The same human is
 * routinely all three at once: an unknown WhatsApp number today, a Lead tomorrow, a Customer next
 * week, and a Lead again next season. Keying a thread on {@code customer_id} means the messages
 * that arrived before they became a customer belong to nothing; keying it on {@code lead_id} means
 * the thread fragments every time a new enquiry opens a new lead. The address is the only thing that
 * stays put, so the address is the key — and {@link #customerId} / {@link #leadId} are soft links
 * that get filled in as the relationship is discovered.
 *
 * <p><b>{@link #identityValue} is always canonical, never as typed.</b> E.164 for
 * {@link ContactIdentityType#PHONE} via {@code PhoneCanonicalizer} — the same authority
 * {@code CustomerMatcher} uses, so a thread and a customer match can never disagree — and
 * lower-cased for {@link ContactIdentityType#EMAIL}. Storing the raw form would make
 * {@code +919812345678} and {@code +91 98123 45678} two different people.
 *
 * <p>Unique on {@code (tenant_id, identity_type, identity_value) WHERE deleted_at IS NULL}. The
 * {@code deleted_at} arm is load-bearing: without it a soft-deleted row keeps the key forever while
 * the {@code …AndDeletedAtIsNull} finder cannot see it, so the next inbound message takes the INSERT
 * branch and hits the constraint.
 */
@Entity
@Table(name = "comm_contact_identities", indexes = {
        @Index(name = "idx_cci_tenant",   columnList = "tenant_id"),
        @Index(name = "idx_cci_customer", columnList = "customer_id"),
        @Index(name = "idx_cci_lead",     columnList = "lead_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CommContactIdentity extends BaseTenantEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "identity_type", nullable = false, length = 10)
    private ContactIdentityType identityType;

    /** Canonical E.164 or lower-cased email. NEVER the raw user input. */
    @Column(name = "identity_value", nullable = false, length = 190)
    private String identityValue;

    /**
     * Best-known display name. Denormalised so the inbox list never joins to customers/leads;
     * refreshed when a better source appears (a linked Customer beats a WhatsApp profile name).
     */
    @Column(name = "display_name", length = 150)
    private String displayName;

    // ── Soft links. Logical FKs, no DB constraint — same pattern as Booking.customerId. ─────────

    /** {@code customers.id} once this address is recognised as an existing customer. */
    @Column(name = "customer_id")
    private Long customerId;

    /** Denormalised so a DTO can expose the customer without a second query. */
    @Column(name = "customer_public_id")
    private UUID customerPublicId;

    /**
     * {@code leads.id} of the most recent NON-TERMINAL lead on this address.
     *
     * <p>Deliberately "most recent", not "the" lead: one contact legitimately generates many leads
     * over the years, and the inbox only ever needs the one currently in play. The full history is
     * reachable through the customer.
     */
    @Column(name = "lead_id")
    private Long leadId;

    @Column(name = "lead_public_id")
    private UUID leadPublicId;

    /** Set when this address is known to be unreachable (hard bounce, number not on WhatsApp). */
    @Column(name = "unreachable_since")
    private LocalDateTime unreachableSince;

    @Column(name = "unreachable_reason", length = 255)
    private String unreachableReason;

    /** True once a customer or lead has been attached — i.e. we know who this is. */
    public boolean isIdentified() {
        return customerId != null || leadId != null;
    }
}
