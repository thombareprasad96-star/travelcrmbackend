package com.crm.travelcrm.communication.enums;

/**
 * The kind of address a contact identity holds — one half of the natural key
 * {@code (tenant_id, identity_type, identity_value)}.
 *
 * <p>{@code identityValue} is always stored CANONICAL, never as typed: E.164 for {@link #PHONE}
 * (via {@code PhoneCanonicalizer}, the same authority {@code CustomerMatcher} uses) and lower-cased
 * for {@link #EMAIL}. Storing the raw form would make {@code +919812345678} and
 * {@code +91 98123 45678} two identities, which splits one human's thread in two.
 */
public enum ContactIdentityType {

    /** Canonical E.164, e.g. {@code +919812345678}. WhatsApp, SMS and calls key on this. */
    PHONE,

    /** Lower-cased address. Email keys on this. */
    EMAIL
}
