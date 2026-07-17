package com.crm.travelcrm.leadsource.spi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * One inbound HTTP delivery, as an adapter sees it.
 *
 * <p><b>{@link #body()} raw bytes are mandatory and irreplaceable.</b> An HMAC is computed over the
 * EXACT received bytes; re-serializing a parsed body changes them and the signature check then fails
 * for every honest caller. This is why the webhook controller binds {@code @RequestBody byte[]}.
 *
 * <p>Bytes-only would force every form-POST adapter to re-implement form decoding, so the typed
 * accessors are provided — lazily, cached, over the <em>same</em> immutable bytes.
 *
 * <p>{@link #credentials()} is an immutable view, never the entity: the gateway is non-transactional
 * and {@code open-in-view=false}, so the row is detached and a lazy field would throw on the webhook
 * thread.
 */
public interface RawInbound {

    /** The exact received bytes. The HMAC input. Never re-serialized. */
    byte[] body();

    /** The body parsed as JSON — lazy, cached. Null when the body is absent or not JSON. */
    JsonNode json();

    /** The body parsed as {@code application/x-www-form-urlencoded} — lazy, cached. */
    Map<String, String> form();

    /** Case-insensitive header lookup. Null when absent. */
    String header(String name);

    /** Query-string parameter. Null when absent. */
    String query(String name);

    /**
     * Every query-string parameter.
     *
     * <p><b>Enumeration, not just lookup, because a channel can carry its whole payload here.</b>
     * {@link #query(String)} answers "is the signature present?" — it cannot answer "what did the
     * provider actually send?", which is the only question worth asking of a channel whose contract is
     * undocumented. An adapter that can only probe names it already believes in can never discover it
     * was wrong.
     *
     * <p>Keys are as received: case-sensitive, undecoded of nothing. Adapters that alias field names
     * should normalise the case themselves.
     */
    Map<String, String> queryAll();

    /** This connection's decrypted credential bag. Empty for channels that need none. */
    IntegrationCredentials credentials();
}
