package com.crm.travelcrm.task.specification;

import com.crm.travelcrm.task.entity.Task;
import com.crm.travelcrm.task.enums.TaskCategory;
import com.crm.travelcrm.task.enums.TaskPriority;
import com.crm.travelcrm.task.enums.TaskStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * JPA Specifications for {@link Task} list / calendar queries. Every build always AND-s
 * {@code tenant_id} + {@code deleted_at IS NULL}; the sub-agent row scope is layered on separately
 * via {@link #ownedBy(Long)} only when {@code SubAgentScope.ownerFilter()} is non-null.
 *
 * <p>The date-range predicate filters on the calendar anchor {@code COALESCE(startAt, dueDate)} so a
 * task appears in exactly the window where it is plotted (matching {@code Task.calendarInstant()}).
 */
public final class TaskSpecification {

    private TaskSpecification() {
    }

    public static Specification<Task> build(Long tenantId,
                                            TaskStatus status,
                                            TaskPriority priority,
                                            TaskCategory category,
                                            Long assigneeUserId,
                                            Instant from,
                                            Instant to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            predicates.add(cb.isNull(root.get("deletedAt")));

            if (status != null)         predicates.add(cb.equal(root.get("status"), status));
            if (priority != null)       predicates.add(cb.equal(root.get("priority"), priority));
            if (category != null)       predicates.add(cb.equal(root.get("category"), category));
            if (assigneeUserId != null) predicates.add(cb.equal(root.get("assignToUserId"), assigneeUserId));

            if (from != null || to != null) {
                Expression<Instant> anchor = cb.coalesce(root.get("startAt"), root.get("dueDate"));
                if (from != null && to != null) {
                    predicates.add(cb.between(anchor, from, to));
                } else if (from != null) {
                    predicates.add(cb.greaterThanOrEqualTo(anchor, from));
                } else {
                    predicates.add(cb.lessThanOrEqualTo(anchor, to));
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** Sub-agent row scope: confine to rows owned by {@code ownerUserId}. */
    public static Specification<Task> ownedBy(Long ownerUserId) {
        return (root, query, cb) -> cb.equal(root.get("ownerUserId"), ownerUserId);
    }

    // ── All Tasks screen ─────────────────────────────────────────────────────────────────────

    /**
     * {@code ScopeResolver} row scope on the ASSIGNEE, plus unassigned rows.
     *
     * <p>Unassigned tasks stay visible to everyone whose scope is not NONE — see
     * {@code TaskAccessGuard}'s class javadoc. Hiding unclaimed work from everyone who could pick
     * it up is how it stays unclaimed.
     */
    public static Specification<Task> assignedToAnyOrUnassigned(Collection<Long> visibleAssignees) {
        return (root, query, cb) -> cb.or(
                cb.isNull(root.get("assignToUserId")),
                root.get("assignToUserId").in(visibleAssignees));
    }

    /** Restrict to the open (outstanding) states — every date tab except ALL. */
    public static Specification<Task> openOnly(Collection<TaskStatus> openStatuses) {
        return (root, query, cb) -> root.get("status").in(openStatuses);
    }

    /**
     * Calendar anchor strictly before {@code instant} — the Overdue tab.
     *
     * <p>Anchor is {@code COALESCE(startAt, dueDate)}, matching {@link Task#isOverdue()} exactly, so
     * the row-level "Overdue" badge and the tab membership can never disagree.
     */
    public static Specification<Task> anchorBefore(Instant instant) {
        return (root, query, cb) -> cb.lessThan(anchor(root, cb), instant);
    }

    /** Calendar anchor on or after {@code instant} — the Upcoming tab. */
    public static Specification<Task> anchorAtOrAfter(Instant instant) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(anchor(root, cb), instant);
    }

    /** Calendar anchor inside {@code [start, end)} — the Today / Yesterday tabs. */
    public static Specification<Task> anchorBetween(Instant start, Instant end) {
        return (root, query, cb) -> cb.and(
                cb.greaterThanOrEqualTo(anchor(root, cb), start),
                cb.lessThan(anchor(root, cb), end));
    }

    /**
     * Free-text search across the columns the grid actually renders, so what the user sees is what
     * they can search: description/title, guest, trip code and assignee.
     */
    public static Specification<Task> search(String term) {
        return (root, query, cb) -> {
            String like = "%" + term.trim().toLowerCase() + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("title")), like),
                    cb.like(cb.lower(root.get("description")), like),
                    cb.like(cb.lower(root.get("customerNameSnapshot")), like),
                    cb.like(cb.lower(root.get("bookingCode")), like),
                    cb.like(cb.lower(root.get("leadName")), like),
                    cb.like(cb.lower(root.get("assignToName")), like));
        };
    }

    /** {@code COALESCE(start_at, due_date)} — the single source of truth for "when is this due". */
    private static Expression<Instant> anchor(Root<Task> root, CriteriaBuilder cb) {
        return cb.coalesce(root.get("startAt"), root.get("dueDate"));
    }
}