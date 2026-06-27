package com.crm.travelcrm.reminder.repository;

import com.crm.travelcrm.reminder.entity.Reminder;
import com.crm.travelcrm.reminder.entity.ReminderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReminderRepository
        extends JpaRepository<Reminder, Long>, JpaSpecificationExecutor<Reminder> {

    Optional<Reminder> findByIdAndTenantIdAndDeletedAtIsNull(Long id, Long tenantId);

    /** External-id lookup used by the Follow-up Report's complete / bulk-complete actions. */
    Optional<Reminder> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    List<Reminder> findByTenantIdAndLeadNameIgnoreCaseAndDeletedAtIsNullOrderByDueDateAsc(
            Long tenantId, String leadName);

    /** Past-due reminders (Active not-yet-flipped + already-flipped OVERDUE) — for {@code GET /overdue}. */
    List<Reminder> findByTenantIdAndStatusInAndDueDateLessThanAndDeletedAtIsNullOrderByDueDateAsc(
            Long tenantId, Collection<ReminderStatus> statuses, Instant now);

    long countByTenantIdAndStatusInAndDueDateLessThanAndDeletedAtIsNull(
            Long tenantId, Collection<ReminderStatus> statuses, Instant now);

    /** Scheduler flip pass — Active reminders whose due-time has passed (all tenants). */
    List<Reminder> findByStatusAndDueDateLessThanAndDeletedAtIsNull(
            ReminderStatus status, Instant now);

    /** Active reminders whose due-time falls inside [start, end) — used by {@code GET /due-today}. */
    List<Reminder> findByTenantIdAndStatusAndDueDateBetweenAndDeletedAtIsNullOrderByDueDateAsc(
            Long tenantId, ReminderStatus status, Instant start, Instant end);

    long countByTenantIdAndDeletedAtIsNull(Long tenantId);

    long countByTenantIdAndStatusAndDeletedAtIsNull(Long tenantId, ReminderStatus status);

    long countByTenantIdAndStatusAndDueDateLessThanAndDeletedAtIsNull(
            Long tenantId, ReminderStatus status, Instant now);

    /**
     * Scheduler poll — runs with NO tenant context, so it deliberately spans all tenants.
     * Tenant isolation is re-applied per-row by the caller before publishing each event.
     */
    List<Reminder> findByStatusAndNotifiedFalseAndDueDateLessThanEqualAndDeletedAtIsNull(
            ReminderStatus status, Instant now);
}