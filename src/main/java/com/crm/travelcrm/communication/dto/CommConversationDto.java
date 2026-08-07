package com.crm.travelcrm.communication.dto;

import com.crm.travelcrm.communication.enums.CommChannel;
import com.crm.travelcrm.communication.enums.ConversationKind;
import com.crm.travelcrm.communication.enums.ConversationPriority;
import com.crm.travelcrm.communication.enums.ConversationStatus;
import com.crm.travelcrm.communication.enums.MessageDirection;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A conversation, as the inbox list row and the conversation-view header.
 *
 * <p>One DTO for both surfaces on purpose: the list already needs the contact, the channel, the
 * status, the assignee and the preview, and the header adds only the CRM link ids. A separate
 * "detail" shape would be the same fields plus four UUIDs.
 *
 * <p>The mockup's right-hand rail (lead stage, destination, travel dates, pax, estimated value)
 * deliberately is NOT inlined here. Those live on the Lead and are fetched by the client from the
 * lead endpoint using {@link #leadPublicId} — duplicating them would mean this row goes stale the
 * moment anyone edits the lead, and would put a join on the hottest list query in the module.
 */
@Getter
@Builder
public class CommConversationDto {

    private final UUID publicId;

    private final ConversationKind kind;
    private final CommChannel channel;
    private final String subject;

    // ── Who ──────────────────────────────────────────────────────────────────────────────────

    private final String contactName;

    /** Canonical address — E.164 or lower-cased email. */
    private final String contactValue;

    private final UUID contactPublicId;

    // ── State ────────────────────────────────────────────────────────────────────────────────

    private final ConversationStatus status;
    private final ConversationPriority priority;
    private final boolean pinned;
    private final LocalDateTime snoozedUntil;

    private final int unreadCount;
    private final int messageCount;

    private final LocalDateTime lastMessageAt;
    private final LocalDateTime lastInboundAt;
    private final MessageDirection lastDirection;
    private final String lastMessagePreview;

    /** True when the contact wrote last — the "pending reply" flag the inbox badges. */
    private final boolean awaitingReply;

    /**
     * Whether a free-text reply is currently permitted on this channel.
     *
     * <p>Always true for email, SMS and internal threads. For WhatsApp it is false outside Meta's
     * 24-hour customer service window, and the composer must switch to the template picker. The
     * server enforces the same rule on send — this field exists so the UI does not offer something
     * that will be rejected.
     */
    private final boolean freeTextAllowed;

    // ── Assignment ───────────────────────────────────────────────────────────────────────────

    private final UUID assignedUserPublicId;
    private final String assignedUserName;

    // ── CRM links. Null until discovered or attached. ─────────────────────────────────────────

    private final UUID leadPublicId;
    private final UUID customerPublicId;
    private final UUID bookingPublicId;
    private final UUID quotationPublicId;
}
