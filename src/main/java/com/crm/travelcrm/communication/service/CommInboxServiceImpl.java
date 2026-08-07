package com.crm.travelcrm.communication.service;

import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.communication.config.CommunicationProperties;
import com.crm.travelcrm.communication.dto.CommConversationDto;
import com.crm.travelcrm.communication.dto.CommInboxSummaryDto;
import com.crm.travelcrm.communication.dto.CommMessageDto;
import com.crm.travelcrm.communication.dto.CommSearchHitDto;
import com.crm.travelcrm.communication.entity.CommContactIdentity;
import com.crm.travelcrm.communication.entity.CommConversation;
import com.crm.travelcrm.communication.entity.CommMessage;
import com.crm.travelcrm.communication.enums.CommChannel;
import com.crm.travelcrm.communication.enums.ConversationKind;
import com.crm.travelcrm.communication.enums.ConversationStatus;
import com.crm.travelcrm.communication.mapper.CommMapper;
import com.crm.travelcrm.communication.repository.CommAttachmentRepository;
import com.crm.travelcrm.communication.repository.CommAttachmentView;
import com.crm.travelcrm.communication.repository.CommCallRepository;
import com.crm.travelcrm.communication.repository.CommContactIdentityRepository;
import com.crm.travelcrm.communication.repository.CommConversationRepository;
import com.crm.travelcrm.communication.repository.CommMessageRepository;
import com.crm.travelcrm.communication.specification.CommConversationSpecification;
import com.crm.travelcrm.permission.enums.Permission;
import com.crm.travelcrm.permission.service.SubAgentScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read side of the Communication Center.
 *
 * <h2>Conventions this class must not break</h2>
 * <ul>
 *   <li><b>{@code @Transactional} on methods only.</b> A class-level annotation fails
 *       {@code LeadSourceAdapterPurityArchTest} AND silently disables the Hibernate tenant filter
 *       for every query in the class — {@code TenantFilterAspect}'s pointcut matches method level
 *       only, and it fails OPEN on a null tenant.</li>
 *   <li><b>Scope is composed once, in {@link #scopedSpec}, and every read goes through it.</b> The
 *       hub tiles are {@code count()} calls over the same specification the list uses, so a tile can
 *       never disagree with the list it links to.</li>
 *   <li><b>Batch, never per-row.</b> Contacts and attachments are fetched with one {@code IN} query
 *       per page. On a 10-connection pool shared with Postgres on a two-vCPU box, an N+1 in the
 *       inbox is not a slow page — it is the whole application.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CommInboxServiceImpl implements CommInboxService {

    private final CommConversationRepository conversationRepository;
    private final CommMessageRepository messageRepository;
    private final CommContactIdentityRepository contactRepository;
    private final CommAttachmentRepository attachmentRepository;
    private final CommCallRepository callRepository;
    private final UserRepository userRepository;
    private final CommAccessGuard guard;
    private final SubAgentScope subAgentScope;
    private final CommMapper mapper;
    private final CommunicationProperties properties;

    /** Placeholder for the {@code assignees} bind when scope is ALL — never actually compared. */
    private static final Collection<Long> IGNORED_ASSIGNEES = List.of(-1L);

    // ── List ─────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<CommConversationDto> listConversations(int page,
                                                                   int size,
                                                                   String channel,
                                                                   String status,
                                                                   Boolean unreadOnly,
                                                                   Boolean awaitingReplyOnly,
                                                                   UUID assignedTo,
                                                                   String q,
                                                                   LocalDateTime from,
                                                                   LocalDateTime to) {
        Long tenantId = guard.requireTenantId();
        Set<Long> visible = guard.visibleAssigneeIds(Permission.COMM_READ.key());

        // Scope NONE. Returning an empty page rather than an unfiltered one is the whole point —
        // an empty visible set must never be passed to a specification as "no restriction".
        if (visible != null && visible.isEmpty()) {
            return emptyPage(page, size, "No conversations");
        }

        Specification<CommConversation> spec = scopedSpec(
                tenantId, visible,
                CommConversationSpecification.build(
                        tenantId, ConversationKind.CUSTOMER,
                        parseEnum(CommChannel.class, channel, "channel"),
                        parseEnum(ConversationStatus.class, status, "status"),
                        unreadOnly, awaitingReplyOnly, q, from, to));

        if (assignedTo != null) {
            spec = spec.and(CommConversationSpecification.assignedTo(resolveUserId(assignedTo, tenantId)));
        }

        Pageable pageable = PageRequest.of(
                Math.max(page, 0), clampSize(size),
                Sort.by(Sort.Direction.DESC, "pinned").and(Sort.by(Sort.Direction.DESC, "lastMessageAt")));

        Page<CommConversation> found = conversationRepository.findAll(spec, pageable);
        return PagedApiResponse.of("Conversations fetched",
                mapper.toConversationDtos(found.getContent(), loadContacts(tenantId, found.getContent())),
                PaginationMeta.from(found, "lastMessageAt", "desc"));
    }

    // ── Hub tiles ────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public CommInboxSummaryDto summary() {
        Long tenantId = guard.requireTenantId();
        Set<Long> visible = guard.visibleAssigneeIds(Permission.COMM_READ.key());
        Long ownerFilter = subAgentScope.ownerFilter();

        if (visible != null && visible.isEmpty()) {
            return CommInboxSummaryDto.builder().build();
        }

        Specification<CommConversation> base = scopedSpec(
                tenantId, visible,
                CommConversationSpecification.build(
                        tenantId, ConversationKind.CUSTOMER, null, null, null, null, null, null, null));

        LocalDateTime since = LocalDateTime.now().minus(properties.getSummaryWindow());

        CommInboxSummaryDto.CommInboxSummaryDtoBuilder builder = CommInboxSummaryDto.builder()
                .totalConversations(conversationRepository.count(base))
                .unreadConversations(conversationRepository.count(
                        base.and(CommConversationSpecification.unread())))
                .pendingReplies(conversationRepository.count(
                        base.and(CommConversationSpecification.awaitingReply())))
                .missedCalls(callRepository.countMissed(tenantId, since, ownerFilter))
                .dueFollowUps(0L);

        for (Object[] row : conversationRepository.countByChannel(
                tenantId, ConversationKind.CUSTOMER, ownerFilter,
                visible == null, visible == null ? IGNORED_ASSIGNEES : visible)) {
            builder.channelCount(((CommChannel) row[0]).name(), (Long) row[1]);
        }
        return builder.build();
    }

    // ── One conversation ─────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public CommConversationDto getConversation(UUID publicId) {
        CommConversation conversation = guard.requireVisible(publicId, Permission.COMM_READ.key());
        CommContactIdentity contact = conversation.getContactIdentityId() == null
                ? null
                : contactRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                        conversation.getContactIdentityId(), conversation.getTenantId()).orElse(null);
        return mapper.toDto(conversation, contact);
    }

    // ── Timeline ─────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<CommMessageDto> listMessages(UUID conversationPublicId, int page, int size) {
        CommConversation conversation = guard.requireVisible(conversationPublicId, Permission.COMM_READ.key());
        Long tenantId = conversation.getTenantId();

        // Private-note visibility is applied in the QUERY, never after the fetch — see
        // CommMessageRepository. Filtering in Java would mean the rows were materialised inside a
        // request that was not entitled to them.
        Page<CommMessage> found = messageRepository.findTimeline(
                tenantId, conversation.getId(),
                guard.canReadPrivateNotes(), guard.currentUserId(),
                PageRequest.of(Math.max(page, 0), clampSize(size)));

        Map<Long, List<CommAttachmentView>> attachments = loadAttachments(tenantId, found.getContent());
        Map<Long, UUID> callPublicIds = loadCallPublicIds(tenantId, found.getContent());

        List<CommMessageDto> dtos = found.getContent().stream()
                .map(m -> mapper.toDto(m, conversation.getPublicId(),
                        attachments.get(m.getId()), callPublicIds.get(m.getCallId())))
                .toList();

        return PagedApiResponse.of("Messages fetched", dtos,
                PaginationMeta.from(found, "occurredAt", "desc"));
    }

    // ── Search ───────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<CommSearchHitDto> search(String q, int page, int size) {
        Long tenantId = guard.requireTenantId();
        if (q == null || q.isBlank()) {
            return PagedApiResponse.of("Search results", List.of(), emptyMeta(page, size));
        }

        Set<Long> visible = guard.visibleAssigneeIds(Permission.COMM_READ.key());
        if (visible != null && visible.isEmpty()) {
            return PagedApiResponse.of("Search results", List.of(), emptyMeta(page, size));
        }

        // The native full-text query carries the sub-agent owner filter only. The assignment axis is
        // applied below against the conversations actually resolved, because expressing "assigned to
        // one of N, or unassigned" inside the native SQL would mean binding a variable-length list
        // into a string-built statement — the one shape that invites an injection bug.
        Page<CommMessage> hits = messageRepository.searchFullText(
                tenantId, q.trim(),
                guard.canReadPrivateNotes(), guard.currentUserId(),
                subAgentScope.ownerFilter(),
                PageRequest.of(Math.max(page, 0), clampSize(size)));

        Map<Long, CommConversation> conversations = loadConversations(tenantId, hits.getContent());

        // One pass, one hit per matching MESSAGE. A thread the caller may not see drops out here;
        // the page's total therefore counts rows before the assignment filter, which is deliberate —
        // narrowing it would require the variable-length assignee list inside the native statement.
        List<CommSearchHitDto> dtos = hits.getContent().stream()
                .map(m -> {
                    CommConversation c = conversations.get(m.getConversationId());
                    return c != null && isAssigneeVisible(c, visible) ? mapper.toSearchHit(m, c) : null;
                })
                .filter(Objects::nonNull)
                .toList();

        return PagedApiResponse.of("Search results", dtos,
                PaginationMeta.from(hits, "occurredAt", "desc"));
    }

    // ── Scope composition ────────────────────────────────────────────────────────────────────

    /**
     * Layers BOTH row-scope dimensions onto a base specification.
     *
     * <p>They are different questions and both must be asked: {@code SubAgentScope} confines a B2B
     * partner to threads it OWNS, while {@code ScopeResolver} confines everyone to threads assigned
     * within their OWN/TEAM/ALL visibility. A sub-agent is subject to both.
     */
    private Specification<CommConversation> scopedSpec(Long tenantId,
                                                        Set<Long> visibleAssignees,
                                                        Specification<CommConversation> base) {
        Specification<CommConversation> spec = base;

        Long ownerFilter = subAgentScope.ownerFilter();
        if (ownerFilter != null) {
            spec = spec.and(CommConversationSpecification.ownedBy(ownerFilter));
        }
        if (visibleAssignees != null) {
            spec = spec.and(CommConversationSpecification.assignedToAnyOrUnassigned(visibleAssignees));
        }
        return spec;
    }

    /** Mirrors {@code assignedToAnyOrUnassigned} for rows resolved outside a specification. */
    private boolean isAssigneeVisible(CommConversation c, Set<Long> visible) {
        return visible == null
                || c.getAssignedUserId() == null
                || visible.contains(c.getAssignedUserId());
    }

    // ── Batch loaders (no N+1) ───────────────────────────────────────────────────────────────

    private Map<Long, CommContactIdentity> loadContacts(Long tenantId, List<CommConversation> conversations) {
        List<Long> ids = conversations.stream()
                .map(CommConversation::getContactIdentityId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return contactRepository.findByTenantIdAndIdInAndDeletedAtIsNull(tenantId, ids).stream()
                .collect(Collectors.toMap(CommContactIdentity::getId, Function.identity(), (a, b) -> a));
    }

    private Map<Long, List<CommAttachmentView>> loadAttachments(Long tenantId, List<CommMessage> messages) {
        List<Long> withFiles = messages.stream()
                .filter(m -> m.getAttachmentCount() > 0)
                .map(CommMessage::getId)
                .toList();
        if (withFiles.isEmpty()) {
            return Map.of();
        }
        return attachmentRepository.findByTenantIdAndMessageIdInAndDeletedAtIsNull(tenantId, withFiles)
                .stream()
                .collect(Collectors.groupingBy(CommAttachmentView::getMessageId));
    }

    private Map<Long, UUID> loadCallPublicIds(Long tenantId, List<CommMessage> messages) {
        List<Long> callIds = messages.stream()
                .map(CommMessage::getCallId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (callIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, UUID> out = new LinkedHashMap<>();
        callRepository.findByTenantIdAndIdInAndDeletedAtIsNull(tenantId, callIds)
                .forEach(c -> out.put(c.getId(), c.getPublicId()));
        return out;
    }

    private Map<Long, CommConversation> loadConversations(Long tenantId, List<CommMessage> messages) {
        List<Long> ids = messages.stream().map(CommMessage::getConversationId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return conversationRepository.findByTenantIdAndIdInAndDeletedAtIsNull(tenantId, ids).stream()
                .collect(Collectors.toMap(CommConversation::getId, Function.identity(), (a, b) -> a));
    }

    // ── Small helpers ────────────────────────────────────────────────────────────────────────

    /**
     * Bounds the page size.
     *
     * <p>Not cosmetic: without it {@code ?size=1000000} pulls a tenant's entire message history into
     * one heap, on a box sized for a pilot.
     */
    private int clampSize(int requested) {
        if (requested <= 0) {
            return properties.getDefaultPageSize();
        }
        return Math.min(requested, properties.getMaxPageSize());
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String label) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException("Unknown " + label + ": " + raw);
        }
    }

    private Long resolveUserId(UUID userPublicId, Long tenantId) {
        return userRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(userPublicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userPublicId))
                .getId();
    }

    private PagedApiResponse<CommConversationDto> emptyPage(int page, int size, String message) {
        return PagedApiResponse.of(message, List.of(), emptyMeta(page, size));
    }

    private PaginationMeta emptyMeta(int page, int size) {
        return PaginationMeta.from(
                new PageImpl<>(List.of(), PageRequest.of(Math.max(page, 0), clampSize(size)), 0));
    }
}
