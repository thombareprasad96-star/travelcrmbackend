package com.crm.travelcrm.leadsource.spi;

import java.util.Optional;

/**
 * A provider-supplied identity for one delivery, used to drop duplicates.
 *
 * <p>Providers retry. A retry after a successful-but-slowly-ACKed delivery is indistinguishable from
 * a new lead unless the payload carries something stable to key on.
 *
 * <p>{@link #none()} is the honest answer when a provider offers nothing stable — and it must be,
 * because inventing a key by hashing the whole body would treat two genuine enquiries from the same
 * person with the same text as one lead.
 */
public record IdempotencyKey(String value) {

    private static final IdempotencyKey NONE = new IdempotencyKey(null);

    /** This provider gives us nothing stable to deduplicate on. */
    public static IdempotencyKey none() {
        return NONE;
    }

    public static IdempotencyKey of(String value) {
        return (value == null || value.isBlank()) ? NONE : new IdempotencyKey(value.trim());
    }

    public Optional<String> asOptional() {
        return Optional.ofNullable(value);
    }

    public boolean isPresent() {
        return value != null;
    }
}
