package com.crm.travelcrm.leadsource.spi;

import java.util.List;

/**
 * What an adapter made of an inbound payload.
 *
 * <p>Sealed and explicit because the alternatives all lose information the framework needs:
 *
 * <ul>
 *   <li><b>{@link Complete} is a LIST.</b> Meta batches multiple {@code entry[].changes[]} per POST —
 *       one delivery yields N leads. A singular return is unfixable in place.</li>
 *   <li><b>{@link Deferred} is a LIST</b> for the same reason, and is <b>durable — persisted before
 *       the provider ACK</b>, so a crash between ACK and fetch is recoverable. That is the entire
 *       reason the ACK comes first, and it works only because parse and fetch are separate objects.</li>
 *   <li><b>{@link Ignored} is not politeness.</b> Most real traffic on these endpoints is not a lead:
 *       subscription echoes, test pings, delivery-status callbacks. A bare return has no way to say
 *       "valid, not a lead" — {@code null} is indistinguishable from a bug, and throwing turns a
 *       routine ping into a retry storm. The reason is counted.</li>
 *   <li><b>{@link Echo} carries a response body</b> for a provider handshake (Meta's GET
 *       {@code hub.challenge}). It is a documented, deliberate exception to the {@code ApiResponse}
 *       envelope rule, and the only one in this framework.</li>
 * </ul>
 */
public sealed interface InboundParseResult {

    /** N leads, fully readable from this payload alone. */
    record Complete(List<NormalizedLead> leads) implements InboundParseResult {
        public Complete {
            leads = List.copyOf(leads);
        }
    }

    /**
     * The payload only identifies leads; their content must be fetched from the provider's API
     * (Meta delivers a bare {@code leadgen_id}).
     *
     * <p>Handles are persisted BEFORE the provider is ACKed, then fetched on a background thread in
     * its own transaction — a different failure domain entirely (transport/401, and worth retrying,
     * unlike a bad payload).
     */
    record Deferred(List<FetchHandle> handles) implements InboundParseResult {
        public Deferred {
            handles = List.copyOf(handles);
        }
    }

    /**
     * Valid traffic that is not a lead.
     *
     * @param reason a short, LOW-CARDINALITY label — it is used as a metric/counter dimension, so it
     *               must never embed an id, a phone number or a timestamp
     */
    record Ignored(String reason) implements InboundParseResult {}

    /**
     * Reply with these exact bytes — a provider handshake.
     *
     * <p>The GET that carries such a handshake needs an explicit permit: the existing security rule is
     * POST-only, so a GET otherwise falls through to {@code authenticated()} and 401s.
     */
    record Echo(byte[] body, String contentType) implements InboundParseResult {}
}
