package com.crm.travelcrm.communication.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Workflow state of a conversation — the inbox's primary filter.
 *
 * <p>Distinct from "unread". A thread can be read and still {@link #OPEN} (the agent looked at it
 * and has not answered), which is exactly the "pending replies" figure on the hub.
 */
public enum ConversationStatus {

    /** Live and needs attention. The default for anything inbound. */
    OPEN,

    /** Answered; waiting on the contact. Counts as handled, not closed. */
    PENDING,

    /** Deliberately hidden until {@code snoozed_until}. */
    SNOOZED,

    /** Done. Stays fully searchable — closing is not deleting. */
    CLOSED;

    /** States that still belong in the working inbox. */
    public static final Set<ConversationStatus> ACTIVE = EnumSet.of(OPEN, PENDING);

    public boolean isActive() {
        return ACTIVE.contains(this);
    }
}
