package com.crm.travelcrm.task.specification;

import com.crm.travelcrm.task.entity.Task;
import com.crm.travelcrm.task.enums.TaskCategory;
import com.crm.travelcrm.task.enums.TaskPriority;
import com.crm.travelcrm.task.enums.TaskStatus;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
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
}