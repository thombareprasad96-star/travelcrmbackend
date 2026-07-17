package com.crm.travelcrm.leadsource.spi;

import java.util.Map;
import java.util.Optional;

/**
 * An immutable view of one connection's decrypted credential bag — <b>never the entity</b>.
 *
 * <p><b>Why this type exists at all.</b> {@code spring.jpa.open-in-view=false} and the ingest gateway
 * is non-transactional, so a {@code LeadSourceIntegration} handed to an adapter would be
 * <b>detached</b>: touching a lazy field during verify or parse throws
 * {@code LazyInitializationException} on a webhook thread — a failure that never appears in a
 * {@code byte[]}-fixture unit test, only in production. Mapping the row into this record at resolution
 * time makes the whole question moot.
 *
 * <p>It is also what keeps adapters PURE: no repository, no {@code EntityManager}, no
 * {@code TenantContext}. Purity is not aesthetics — it is what makes a stored raw payload replayable.
 */
public record IntegrationCredentials(Map<String, String> values) {

    public IntegrationCredentials {
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    public static IntegrationCredentials empty() {
        return new IntegrationCredentials(Map.of());
    }

    /** Empty when absent OR blank — a blank secret must never read as "configured". */
    public Optional<String> get(String key) {
        String v = values.get(key);
        return (v == null || v.isBlank()) ? Optional.empty() : Optional.of(v);
    }

    public boolean has(String key) {
        return get(key).isPresent();
    }

    /**
     * Never renders the values. A credential bag reaching a log line is the failure this override
     * exists to prevent, and {@code toString()} is exactly how it would get there.
     */
    @Override
    public String toString() {
        return "IntegrationCredentials[" + values.size() + " key(s): " + values.keySet() + "]";
    }
}
