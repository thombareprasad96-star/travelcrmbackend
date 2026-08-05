package com.crm.travelcrm.communication.enums;

/**
 * Which way a message travelled.
 *
 * <p>This is the field the existing lead-ingest pipeline throws away, and getting it wrong is a
 * visible product bug rather than a subtle one: Interakt posts the agency's OWN outbound messages
 * back to the same webhook URL as customer messages
 * ({@code WhatsAppInboundAdapter} discriminates on {@code data.message.chat_message_type}).
 * Storing every delivery as {@link #INBOUND} manufactures a conversation of the agency talking to
 * itself.
 */
public enum MessageDirection {

    /** From the contact to us. Advances {@code conversation.lastInboundAt} (the 24 h WhatsApp window). */
    INBOUND,

    /** From us to the contact. */
    OUTBOUND,

    /** Never transmitted: notes, and staff messages inside an internal conversation. */
    INTERNAL
}
