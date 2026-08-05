package com.crm.travelcrm.communication.enums;

/**
 * Delivery state of one message.
 *
 * <p>{@link #SKIPPED} and {@link #FAILED} are deliberately distinct, mirroring
 * {@code MessageDispatcher.DispatchResult}: SKIPPED means we never tried (the contact has no
 * reachable phone/email on this channel), FAILED means the provider rejected it. Collapsing them
 * makes "why didn't this send?" unanswerable in the inbox, and they need different remedies.
 *
 * <p>{@link #DELIVERED} and {@link #READ} are only ever reached from a provider status callback.
 * Until Phase 2 wires those, an outbound message stops at {@link #SENT} — which is honest.
 */
public enum MessageStatus {

    /** Persisted, transport not yet attempted. */
    QUEUED,

    /** Handed to the provider without error. */
    SENT,

    /** Provider confirmed delivery to the device. */
    DELIVERED,

    /** Provider confirmed the contact opened it. */
    READ,

    /** Provider rejected it, or the transport threw. {@code errorMessage} carries why. */
    FAILED,

    /** Never attempted — the contact is unreachable on this channel. */
    SKIPPED,

    /** An inbound message. Terminal by definition. */
    RECEIVED
}
