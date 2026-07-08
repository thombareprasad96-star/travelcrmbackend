package com.crm.travelcrm.common.util;

/**
 * Single source of truth for how a phone number is canonicalised before it is
 * stored or used as the per-tenant customer/lead natural key.
 *
 * <p>Historically each module normalised (or didn't) on its own: the customer
 * module trimmed, the lead→booking conversion path used the raw string. That
 * asymmetry meant a lead phone could fail to match an otherwise-identical
 * customer, spawning a duplicate customer. Every write and every lookup now
 * funnels through here so the key is derived identically everywhere.</p>
 *
 * <p>Normalisation is intentionally conservative — {@code trim()} only — because
 * lead phones are already digit-validated ({@code ^\+?[1-9]\d{7,14}$}) and
 * rewriting the stored format would silently break matches against existing
 * rows. Stronger canonicalisation (stripping separators, E.164) belongs with a
 * deliberate one-time data migration, not a passive read-path change.</p>
 */
public final class PhoneNormalizer {

    private PhoneNormalizer() {
    }

    /** Trimmed phone, or {@code null} if the input is null. */
    public static String normalize(String phone) {
        return phone == null ? null : phone.trim();
    }

    /** True when the phone is null or blank after trimming — i.e. unusable as a key. */
    public static boolean isBlank(String phone) {
        return phone == null || phone.trim().isEmpty();
    }
}