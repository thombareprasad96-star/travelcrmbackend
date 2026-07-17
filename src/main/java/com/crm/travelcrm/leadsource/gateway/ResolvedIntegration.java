package com.crm.travelcrm.leadsource.gateway;

import com.crm.travelcrm.leadsource.spi.IntegrationCredentials;

/**
 * An immutable snapshot of the connection an inbound delivery resolved to.
 *
 * <p><b>The entity must never cross the resolver.</b> {@code spring.jpa.open-in-view=false} and the
 * gateway is non-transactional, so a {@code LeadSourceIntegration} handed onward would be DETACHED:
 * touching a lazy field during verify or parse throws {@code LazyInitializationException} on a webhook
 * thread — a failure that never shows up in a {@code byte[]}-fixture unit test, only in production.
 * Mapping the row into this record at resolution time makes the whole question moot.
 *
 * @param integrationId internal id — the ingest path already holds the row, so a publicId round-trip
 *                      would buy a redundant lookup and nothing else. It never crosses an API boundary.
 * @param tenantId      resolved from the TOKEN (or an HMAC-proven account key), never from the body
 * @param channel       {@code LeadSourceChannel.slug()}, as stored — a plain String
 * @param label         the tenant's name for this connection, for logs and notifications
 * @param enabled       a disabled connection is logged against the right tenant, then rejected
 * @param status        CONNECTED | DEGRADED | DISABLED
 * @param credentials   decrypted at resolution time; empty for channels needing none
 * @param usedPreviousToken true when the PREVIOUS token authenticated this delivery — the tenant has
 *                      rotated but not yet finished repasting the URL at the provider
 */
public record ResolvedIntegration(
        Long integrationId,
        Long tenantId,
        String channel,
        String label,
        boolean enabled,
        String status,
        IntegrationCredentials credentials,
        boolean usedPreviousToken
) {}
