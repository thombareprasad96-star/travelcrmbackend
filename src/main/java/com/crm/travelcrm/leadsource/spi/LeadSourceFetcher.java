package com.crm.travelcrm.leadsource.spi;

import java.util.List;

/**
 * Fetches a lead's content from the provider's API when the webhook only carried a pointer
 * (Meta delivers a bare {@code leadgen_id}).
 *
 * <p>Separate from {@link LeadSourceAdapter} because it lives in a different failure domain: it runs
 * on a background thread AFTER the provider has been ACKed, in its own transaction, and its failures
 * (transport, 401) are worth RETRYING — unlike a bad payload, which never is.
 *
 * <p><b>Credential freshness is this contract, not an implementation detail</b> — it is the one place
 * the gap is guaranteed to bite:
 * <ul>
 *   <li>{@link FetchHandle} persists ONLY the provider pointer, <b>never credentials</b>.</li>
 *   <li>The drain re-resolves the integration row <b>per attempt</b>, so a tenant who reconnects after
 *       a token expiry has their queued handles succeed, and a rotated token is picked up
 *       automatically.</li>
 *   <li>A soft-deleted or disabled integration <b>abandons</b> its pending handles with a terminal
 *       status — not {@code FAILED}. It must not retry.</li>
 * </ul>
 *
 * <p><b>Tokens are not auto-refreshed.</b> A Meta page token needs a fresh user authorization and
 * cannot be renewed unattended, so an auto-refresh job would be a lie that fails exactly when it
 * matters. A 401/expiry sets the connection {@code DEGRADED} and notifies the tenant's admins.
 *
 * <p>Implementations are Spring singletons shared across every tenant's traffic: <b>no mutable
 * instance state</b>.
 */
public interface LeadSourceFetcher {

    /** The channel this fetcher serves. Must match an adapter's channel. */
    LeadSourceChannel channel();

    /**
     * Retrieve the lead(s) the handle points at.
     *
     * <p>Throw on a transport error or a 401 — the caller counts the attempt and retries. Return an
     * empty list only when the provider authoritatively says the lead is gone (some expire), which is
     * terminal, not retryable.
     *
     * @param creds freshly re-resolved for THIS attempt — never cached from when the handle was made
     */
    List<NormalizedLead> fetch(FetchHandle handle, IntegrationCredentials creds);
}
