package com.crm.travelcrm.communication.specification;

import com.crm.travelcrm.communication.entity.CommConversation;
import com.crm.travelcrm.communication.enums.CommChannel;
import com.crm.travelcrm.communication.enums.ConversationKind;
import com.crm.travelcrm.communication.enums.ConversationStatus;
import com.crm.travelcrm.communication.enums.MessageDirection;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Inbox list predicates.
 *
 * <p><b>{@code tenantId} is passed in and AND-ed explicitly, every time.</b> The Hibernate
 * {@code tenantFilter} does cover Specifications when the calling method is {@code @Transactional},
 * but {@code TenantIsolationArchTest} does not check Specifications at all — it is a ban on five
 * repository method names. A Specification missing this predicate compiles and passes every test in
 * the build, so it is written out here rather than relied upon. This mirrors
 * {@code TaskSpecification.build(tenantId, …)}.
 *
 * <p>Row scope is layered separately by the caller: {@link #ownedBy(Long)} for the sub-agent
 * restriction, {@link #assignedToAny(Collection)} for the OWN/TEAM/ALL axis that implements
 * "assigned vs all conversations". They are different questions and must not be merged — a manager
 * scoped to TEAM sees threads assigned to their team regardless of who created them.
 */
public final class CommConversationSpecification {

    private CommConversationSpecification() {
    }

    public static Specification<CommConversation> build(Long tenantId,
                                                        ConversationKind kind,
                                                        CommChannel channel,
                                                        ConversationStatus status,
                                                        Boolean unreadOnly,
                                                        Boolean awaitingReplyOnly,
                                                        String q,
                                                        LocalDateTime from,
                                                        LocalDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            predicates.add(cb.isNull(root.get("deletedAt")));

            if (kind != null)    predicates.add(cb.equal(root.get("kind"), kind));
            if (channel != null) predicates.add(cb.equal(root.get("channel"), channel));
            if (status != null)  predicates.add(cb.equal(root.get("status"), status));

            if (Boolean.TRUE.equals(unreadOnly)) {
                predicates.add(cb.greaterThan(root.get("unreadCount"), 0));
            }

            if (Boolean.TRUE.equals(awaitingReplyOnly)) {
                predicates.add(cb.notEqual(root.get("status"), ConversationStatus.CLOSED));
                predicates.add(cb.equal(root.get("lastDirection"), MessageDirection.INBOUND));
            }

            // Contact-level search only. Message BODY search is a separate, full-text path
            // (CommMessageRepository.searchFullText) — a LIKE over message bodies on the largest
            // table in the application cannot use an index and would table-scan the tenant.
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("contactName")), like),
                        cb.like(cb.lower(root.get("contactValue")), like),
                        cb.like(cb.lower(root.get("subject")), like),
                        cb.like(cb.lower(root.get("lastMessagePreview")), like)));
            }

            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("lastMessageAt"), from));
            if (to != null)   predicates.add(cb.lessThanOrEqualTo(root.get("lastMessageAt"), to));

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** Sub-agent row scope: confine to threads this user owns. */
    public static Specification<CommConversation> ownedBy(Long ownerUserId) {
        return (root, query, cb) -> cb.equal(root.get("ownerUserId"), ownerUserId);
    }

    /**
     * OWN/TEAM scope: threads assigned to one of {@code userIds}, PLUS unassigned threads.
     *
     * <p>The unassigned arm is the load-bearing half. A message from a number nobody has claimed has
     * {@code assignedUserId = null}; without this clause an OWN-scoped agent cannot see any new
     * enquiry at all, so nobody ever claims one and the inbox appears empty exactly when it matters.
     * Visibility of an unclaimed thread is intentionally as wide as the module itself — the only
     * scope that hides it is NONE, which is handled before this specification is ever built.
     *
     * <p>An empty collection still admits unassigned threads. Callers must therefore treat scope
     * NONE as "return an empty page" before calling this, never as "pass an empty set".
     */
    public static Specification<CommConversation> assignedToAnyOrUnassigned(Collection<Long> userIds) {
        return (root, query, cb) -> userIds.isEmpty()
                ? cb.isNull(root.get("assignedUserId"))
                : cb.or(root.get("assignedUserId").in(userIds), cb.isNull(root.get("assignedUserId")));
    }

    /** Explicit single-assignee filter, from the inbox's "assigned to" dropdown. */
    public static Specification<CommConversation> assignedTo(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("assignedUserId"), userId);
    }

    /** Only threads with unread messages. */
    public static Specification<CommConversation> unread() {
        return (root, query, cb) -> cb.greaterThan(root.get("unreadCount"), 0);
    }

    /** Only threads where the contact wrote last and the thread is still open. */
    public static Specification<CommConversation> awaitingReply() {
        return (root, query, cb) -> cb.and(
                cb.notEqual(root.get("status"), ConversationStatus.CLOSED),
                cb.equal(root.get("lastDirection"), MessageDirection.INBOUND));
    }
}
