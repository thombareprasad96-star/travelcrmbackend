package com.crm.travelcrm.communication.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.communication.dto.CommConversationDto;
import com.crm.travelcrm.communication.dto.CommInboxSummaryDto;
import com.crm.travelcrm.communication.dto.CommMessageDto;
import com.crm.travelcrm.communication.dto.CommSearchHitDto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Read surface of the Communication Center — the unified inbox, one thread's timeline, the hub
 * tiles and global search.
 *
 * <p>Phase 1 is deliberately read-only. Sending, linking and conversions land in later phases;
 * shipping the read path first means the row-scope and entitlement wiring is proven against real
 * data before anything can write.
 *
 * <p>Every method applies the caller's row scope internally. There is no "unscoped" variant and no
 * flag to bypass it — a caller cannot widen its own visibility by choosing a different method.
 */
public interface CommInboxService {

    /**
     * The inbox list.
     *
     * @param channel            filter by {@code CommChannel} name, or null for all
     * @param status             filter by {@code ConversationStatus} name, or null for all
     * @param unreadOnly         only threads with unread messages
     * @param awaitingReplyOnly  only threads where the contact wrote last
     * @param assignedTo         a user's publicId, or null for "anyone I can see"
     * @param q                  matches contact name, address, subject and last-message preview.
     *                           Message BODIES are searched by {@link #search} instead.
     */
    PagedApiResponse<CommConversationDto> listConversations(int page,
                                                            int size,
                                                            String channel,
                                                            String status,
                                                            Boolean unreadOnly,
                                                            Boolean awaitingReplyOnly,
                                                            UUID assignedTo,
                                                            String q,
                                                            LocalDateTime from,
                                                            LocalDateTime to);

    /** The four hub tiles plus the per-channel split, all scoped to what the caller may see. */
    CommInboxSummaryDto summary();

    /** One conversation's header. 404 when missing, cross-tenant or out of scope — never 403. */
    CommConversationDto getConversation(UUID publicId);

    /** One page of a thread, newest first, with private notes the caller may not read excluded. */
    PagedApiResponse<CommMessageDto> listMessages(UUID conversationPublicId, int page, int size);

    /**
     * Full-text search across message bodies.
     *
     * <p>Backed by a Postgres GIN index on a generated {@code tsvector}. Distinct from the {@code q}
     * parameter of {@link #listConversations}, which matches contact/subject metadata only.
     */
    PagedApiResponse<CommSearchHitDto> search(String q, int page, int size);
}
