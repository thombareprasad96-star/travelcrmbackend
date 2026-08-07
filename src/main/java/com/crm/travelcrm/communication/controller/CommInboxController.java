package com.crm.travelcrm.communication.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.communication.dto.CommConversationDto;
import com.crm.travelcrm.communication.dto.CommInboxSummaryDto;
import com.crm.travelcrm.communication.dto.CommMessageDto;
import com.crm.travelcrm.communication.dto.CommSearchHitDto;
import com.crm.travelcrm.communication.service.CommInboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The Communication Center's read API.
 *
 * <p><b>Every path in this module must live under {@code /api/communication/}.</b>
 * {@code ModuleAccessFilter} maps that exact prefix to the {@code COMMUNICATION} plan module, and
 * both the filter and {@code ModuleAccessCoverageTest} match exact-or-followed-by-slash — a sibling
 * controller mapped to {@code /api/communications} (plural) or {@code /api/comm} would be
 * unclassified and would FAIL THE BUILD.
 *
 * <p>Class-level {@code @PreAuthorize} is the read gate; later phases override per method for the
 * write keys. Row-level visibility ("all vs assigned conversations") is NOT expressible here — it is
 * applied inside the service through {@code ScopeResolver} and {@code SubAgentScope}, and out-of-
 * scope reads answer 404 rather than 403 so the existence of another agent's thread is never
 * confirmed.
 *
 * <p>Phase 1 is read-only by design: the row-scope and entitlement wiring is proven against real
 * data before anything in this module can write.
 */
@RestController
@RequestMapping("/api/communication")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('COMM_READ')")
public class CommInboxController {

    private final CommInboxService inboxService;

    /**
     * The unified inbox.
     *
     * <p>{@code q} matches contact name, address, subject and the last-message preview. Searching
     * message BODIES is {@link #search} — a different index and a different cost.
     */
    @GetMapping("/conversations")
    public ResponseEntity<PagedApiResponse<CommConversationDto>> listConversations(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean unreadOnly,
            @RequestParam(required = false) Boolean awaitingReplyOnly,
            @RequestParam(required = false) UUID assignedTo,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

        return ResponseEntity.ok(inboxService.listConversations(
                page, size, channel, status, unreadOnly, awaitingReplyOnly, assignedTo, q, from, to));
    }

    /**
     * Hub tiles: unread, pending replies, missed calls, due follow-ups, and the per-channel split.
     *
     * <p>Declared BEFORE {@code /conversations/{publicId}} would matter if it shared that prefix; it
     * does not, but the ordering convention (literal paths before dynamic ones) is kept so a future
     * {@code /conversations/summary} cannot be swallowed by the id route.
     */
    @GetMapping("/conversations/summary")
    public ResponseEntity<ApiResponse<CommInboxSummaryDto>> summary() {
        return ResponseEntity.ok(ApiResponse.success("Summary fetched", inboxService.summary()));
    }

    /** One conversation's header. 404 when missing, cross-tenant, or outside the caller's scope. */
    @GetMapping("/conversations/{publicId}")
    public ResponseEntity<ApiResponse<CommConversationDto>> getConversation(@PathVariable UUID publicId) {
        return ResponseEntity.ok(
                ApiResponse.success("Conversation fetched", inboxService.getConversation(publicId)));
    }

    /** One page of the timeline, newest first. Private notes the caller may not read are absent. */
    @GetMapping("/conversations/{publicId}/messages")
    public ResponseEntity<PagedApiResponse<CommMessageDto>> listMessages(
            @PathVariable UUID publicId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "30") int size) {

        return ResponseEntity.ok(inboxService.listMessages(publicId, page, size));
    }

    /**
     * Full-text search across message bodies.
     *
     * <p>Runs against a Postgres GIN index on a generated {@code tsvector} — the first full-text
     * index in this codebase. A blank {@code q} returns an empty page rather than everything.
     */
    @GetMapping("/search")
    public ResponseEntity<PagedApiResponse<CommSearchHitDto>> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "25") int size) {

        return ResponseEntity.ok(inboxService.search(q, page, size));
    }
}
