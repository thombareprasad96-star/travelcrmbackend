package com.crm.travelcrm.communication.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.util.Map;

/**
 * The four headline tiles and the channel split on the Communication Center hub.
 *
 * <p>Every figure is computed with a COUNT, never by listing and sizing — the inbox is the largest
 * list in the product and these render on every page load.
 *
 * <p>All figures respect the caller's row scope, so a sub-agent's tiles count only their own
 * threads. A tile that ignored scope would tell a partner how much business the parent agency is
 * doing.
 */
@Getter
@Builder
public class CommInboxSummaryDto {

    /** Threads carrying at least one unread message. */
    private final long unreadConversations;

    /**
     * Threads where the contact wrote last and we have not answered.
     *
     * <p>Deliberately not the same as {@link #unreadConversations}: an agent can read a message and
     * still owe a reply, and that gap is the number worth managing.
     */
    private final long pendingReplies;

    /** Missed calls in the reporting window. */
    private final long missedCalls;

    /**
     * Call follow-ups whose promised time has passed.
     *
     * <p>Sourced from {@code comm_calls.follow_up_at}, not from the Reminders module — this counts
     * "I said I would call back and haven't", which is a property of the call log. A follow-up the
     * agent also turned into a Reminder appears in both places, correctly.
     */
    private final long dueFollowUps;

    private final long totalConversations;

    /** Conversation count per channel, for the tabs and the analytics donut. */
    @Singular("channelCount")
    private final Map<String, Long> byChannel;
}
