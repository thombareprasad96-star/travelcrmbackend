package com.crm.travelcrm.reminder.specification;

import com.crm.travelcrm.reminder.entity.Reminder;
import com.crm.travelcrm.reminder.entity.ReminderPriority;
import com.crm.travelcrm.reminder.entity.ReminderStatus;
import com.crm.travelcrm.reminder.entity.ReminderType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@code GET /api/reminders} filter from the frontend query params
 * ({@code ?status=&priority=&type=}). Always tenant-scoped and excludes soft-deleted rows.
 */
public final class ReminderSpecification {

    private ReminderSpecification() {
    }

    public static Specification<Reminder> build(
            Long tenantId, ReminderStatus status, ReminderPriority priority, ReminderType type) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            predicates.add(cb.isNull(root.get("deletedAt")));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (priority != null) {
                predicates.add(cb.equal(root.get("priority"), priority));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}