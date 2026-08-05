package com.crm.travelcrm.communication.enums;

/**
 * Who is on the other end of a conversation.
 *
 * <p>The discriminator that decides how unread is computed and whether a contact identity is
 * required. {@link #CUSTOMER} threads have exactly one external party and one assigned agent, so a
 * denormalised {@code unread_count} on the conversation is correct. {@link #INTERNAL} threads have N
 * staff members with N genuinely different unread counts, so they read
 * {@code comm_conversation_member.last_read_at} instead.
 */
public enum ConversationKind {

    /** An external contact (lead, customer or unknown number/address). Requires a contact identity. */
    CUSTOMER,

    /** Staff-to-staff: 1:1 or a group channel. Has members, never a contact identity. */
    INTERNAL
}
