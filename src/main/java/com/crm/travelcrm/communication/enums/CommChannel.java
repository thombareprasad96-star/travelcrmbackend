package com.crm.travelcrm.communication.enums;

/**
 * The medium a conversation or message travels over.
 *
 * <p>Deliberately a single vocabulary shared by {@code comm_conversation.channel} and
 * {@code comm_message.channel}: the product's whole point is one timeline, so a UNION across
 * per-channel tables is not an option (see {@code docs/COMMUNICATION_CENTER_DESIGN.md} §3.1).
 *
 * <p>{@link #INTERNAL_NOTE} is a channel rather than a separate table so a note sorts into the
 * customer timeline for free and inherits full-text search, attachments and mentions. It never
 * leaves the system — no transport implements it.
 *
 * <p>Every constant here is written into the {@code comm_conversation_channel_check} and
 * {@code comm_message_channel_check} CHECK constraints (V2 PART 17) and guarded at boot by
 * {@code SchemaEnumConstraintValidator}. Adding a constant WITHOUT refreshing both constraints
 * fails at the first INSERT in production, not at boot.
 */
public enum CommChannel {

    WHATSAPP,
    EMAIL,
    SMS,

    /** A phone call. Logged only — this build never places one (design D1). */
    CALL,

    /** Staff-to-staff chat. Lives on a {@code kind = INTERNAL} conversation. */
    INTERNAL_CHAT,

    /** An internal/customer/private note pinned into a timeline. Never transmitted. */
    INTERNAL_NOTE;

    /** True when messages on this channel are actually delivered to a contact by a transport. */
    public boolean isTransmitted() {
        return this == WHATSAPP || this == EMAIL || this == SMS;
    }
}
