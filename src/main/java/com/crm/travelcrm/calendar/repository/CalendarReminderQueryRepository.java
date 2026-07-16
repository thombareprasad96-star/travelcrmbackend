package com.crm.travelcrm.calendar.repository;

import com.crm.travelcrm.reminder.entity.Reminder;
import org.springframework.data.repository.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Read-only, calendar-owned query surface over {@code reminders} — follow-up events whose due
 * moment falls in the requested window. Declared here (a narrow {@link Repository}) so the reminder
 * module is untouched. Row-scope for sub-agents is applied by the calendar service after the fetch.
 */
public interface CalendarReminderQueryRepository extends Repository<Reminder, Long> {

    List<Reminder> findByTenantIdAndDueDateBetweenAndDeletedAtIsNull(
            Long tenantId, Instant from, Instant to);
}