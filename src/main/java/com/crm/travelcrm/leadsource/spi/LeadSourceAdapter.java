package com.crm.travelcrm.leadsource.spi;

import java.util.Set;

/**
 * Everything a new lead channel needs to implement. <b>Adding a channel is writing one of these.</b>
 *
 * <p><b>Adapters are PURE.</b> No repository, no {@code EntityManager}, no {@code TenantContext}, no
 * Spring data access, no clock, no network. {@link #parse} and {@link #accountKey} are functions over
 * bytes. This is not aesthetics:
 * <ul>
 *   <li>Purity <b>is</b> the tenant-isolation argument — an adapter that cannot query cannot query the
 *       wrong tenant. It is why "tenant resolution must be a method on the adapter" was rejected:
 *       resolution needs a DB lookup by definition.</li>
 *   <li>Purity is what makes a stored raw payload <b>replayable</b> — the same bytes must yield the
 *       same parse next month, on a different machine, with no database.</li>
 * </ul>
 *
 * <p><b>Fetching is a SEPARATE SPI</b> ({@link LeadSourceFetcher}), not a second method here, because
 * the two share none of thread, transaction, credential need or retry semantics:
 *
 * <table>
 *   <caption>Three different failure domains</caption>
 *   <tr><th></th><th>when</th><th>thread / tx</th><th>fails how</th></tr>
 *   <tr><td>verify</td><td>before any state is touched</td><td>webhook request, no tx</td>
 *       <td>reject, never retry</td></tr>
 *   <tr><td>parse</td><td>before the provider ACK</td><td>webhook request, no tx</td>
 *       <td>bad payload — don't retry</td></tr>
 *   <tr><td>fetch</td><td><em>after</em> the ACK</td><td>background, own tx</td>
 *       <td>transport / 401 — <b>do</b> retry</td></tr>
 * </table>
 *
 * An interface whose methods share none of that is lying about its own contract, and every adapter
 * that never fetches would carry a no-op {@code hydrate()}.
 */
public interface LeadSourceAdapter {

    /** The channel this adapter serves. The registry key. */
    LeadSourceChannel channel();

    /** How the framework finds the tenant. Almost always {@link TenantResolution#TOKEN}. */
    default TenantResolution resolution() {
        return TenantResolution.TOKEN;
    }

    /**
     * Extract the provider's account id from the body — <b>{@link TenantResolution#PROVIDER_ACCOUNT}
     * only</b>, and PURE (Meta: {@code entry[0].id}).
     *
     * <p>Throws by default so a channel that declares {@code PROVIDER_ACCOUNT} and forgets to
     * implement this fails loudly at the first delivery rather than resolving to nothing.
     */
    default String accountKey(RawInbound in) {
        throw new UnsupportedOperationException(
                channel().slug() + " does not resolve tenants by provider account");
    }

    /**
     * How this channel's payloads are authenticated. <b>MANDATORY — no default, on purpose.</b>
     *
     * <p>A default here would be fail-open by inheritance: invisible in the adapter's file, and
     * indistinguishable from a deliberate decision. Declaring {@link InboundVerification.TokenOnly}
     * is a sentence someone wrote and a reviewer can see.
     */
    InboundVerification verification();

    /**
     * Turn bytes into leads. <b>PURE — no IO, no DB.</b> Must be replayable from the stored raw
     * payload.
     *
     * <p>Return {@link InboundParseResult.Ignored} for valid traffic that is not a lead (test pings,
     * subscription echoes, delivery receipts) — do NOT throw. Throwing turns a routine ping into a
     * provider retry storm.
     */
    InboundParseResult parse(RawInbound in);

    /** Drives the Integrations UI and the save-time credential check. */
    ChannelCatalogEntry catalog();

    /**
     * JSON paths whose values are redacted before the payload is persisted to
     * {@code lead_ingest_events.raw_payload}.
     *
     * <p><b>Any adapter declaring {@link InboundVerification.SharedSecretInBody} must list that same
     * path here</b>, or a live credential is stored in plaintext next to PII for the retention window.
     *
     * <p>A {@code Set.of()} default is acceptable HERE and not on {@link #verification()} because the
     * fail-open direction is "redact nothing" — visible in the stored payload the moment anyone looks,
     * rather than silent.
     */
    default Set<String> secretFieldPaths() {
        return Set.of();
    }

    /**
     * A provider-stable id for this delivery, so a retry is dropped rather than duplicated.
     * {@link IdempotencyKey#none()} when the provider offers nothing stable — see that type for why
     * inventing one is worse.
     */
    default IdempotencyKey dedupKey(RawInbound in) {
        return IdempotencyKey.none();
    }
}
