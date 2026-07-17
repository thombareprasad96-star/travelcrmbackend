package com.crm.travelcrm.leadsource.spi;

/**
 * How an inbound payload proves it came from the provider — declared as <b>data</b>, executed once by
 * the framework.
 *
 * <p><b>Why not a {@code boolean verify()} on the adapter.</b> Most channels have no signature, so ~12
 * of 16 adapters would write {@code return true} — and a copy-pasted {@code return true} is
 * indistinguishable on review from a deliberate no-signature decision. That is fail-open by
 * boilerplate. A <em>defaulted</em> {@code verify()} is worse: fail-open by inheritance, invisible in
 * the file. A separate verifier-bean registry is worse still — a lookup that can MISS, and a miss in
 * a verifier registry fails open, which is the exact mode this design exists to prevent.
 *
 * <p>{@link TokenOnly} is therefore <b>a statement written in the adapter's file</b> — never an
 * inherited silence, never a runtime fallback when a secret happens to be null.
 *
 * <p><b>Sealed at three, deliberately.</b> There is no {@code Custom} case and no escape hatch: every
 * field a real provider needs must be modelled here, so that adding a channel cannot quietly become
 * "add a bespoke verifier nobody reviews". A provider that does not fit is a design change, on
 * purpose.
 *
 * <p><b>Every verifier fails CLOSED when its secret is unset</b> (the {@code RazorpayGatewayClient}
 * precedent returns false on a null/blank secret). A payload arriving <em>without</em> a signature
 * header is REJECTED, not downgraded — otherwise the strongest channel degrades to the weakest at the
 * attacker's option.
 */
public sealed interface InboundVerification {

    /** How the signature bytes are encoded in the header. */
    enum Enc { HEX, BASE64 }

    /**
     * HMAC over the <b>exact received bytes</b>, compared constant-time.
     *
     * @param header the header carrying the signature, e.g. {@code X-Hub-Signature-256}
     * @param algo   a JCA MAC algorithm, e.g. {@code HmacSHA256}
     * @param enc    how the signature is encoded in the header
     * @param prefix <b>Not cosmetic.</b> Meta sends {@code sha256=<hex>}; Razorpay sends bare hex and
     *               its client compares the header verbatim with no prefix handling — copied as-is
     *               against Meta it fails 100% of the time. Use {@code ""} for bare hex,
     *               {@code "sha256="} for Meta.
     */
    record HmacHeader(String header, String algo, Enc enc, String prefix) implements InboundVerification {}

    /**
     * A shared secret carried inside the body at {@code jsonPath} (the shape JustDial/IndiaMART-style
     * marketplaces tend to use).
     *
     * <p>An adapter declaring this <b>must</b> also list the same path in
     * {@link LeadSourceAdapter#secretFieldPaths()}, or the live credential is persisted in plaintext
     * into {@code lead_ingest_events.raw_payload}, next to PII, for 30 days.
     */
    record SharedSecretInBody(String jsonPath) implements InboundVerification {}

    /**
     * No provider signature: the opaque ingest token in the URL is the only authentication.
     *
     * <p>Legitimate — several marketplaces offer nothing better — but it means anyone holding the URL
     * can post leads, so the token is the whole security boundary. Write it deliberately.
     */
    record TokenOnly() implements InboundVerification {}
}
