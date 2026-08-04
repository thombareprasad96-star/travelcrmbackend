package com.crm.travelcrm.communication.enums;

/**
 * Direction of a logged call — the Call Center's four tabs (All / Incoming / Outgoing / Missed).
 *
 * <p>{@link #MISSED} is modelled as a direction rather than an outcome on purpose: it is how the
 * user filters, it is a headline figure on the hub ("missed calls"), and an unanswered inbound call
 * has no outcome to record. An outbound call nobody picked up is {@link #OUTGOING} with an outcome
 * of {@code NO_ANSWER}.
 */
public enum CallDirection {

    INCOMING,
    OUTGOING,

    /** An inbound call that was never answered. */
    MISSED
}
