package com.crm.travelcrm.reminder.scheduler;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.reminder.entity.Reminder;
import com.crm.travelcrm.reminder.entity.ReminderStatus;
import com.crm.travelcrm.reminder.repository.ReminderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Polls for due reminders once a minute and pushes a "reminder due" notification to the
 * owner's bell via {@link NotifyEvent} — the module's only coupling to the notification module.
 *
 * <p>Runs with no tenant context, so the poll query spans all tenants; isolation is re-applied
 * per row by setting {@link TenantContext} to {@code reminder.tenantId} before the event is
 * published and the {@code notified} flag is flushed.
 *
 * <p>Two passes run each minute (per spec): first fire the due-notification for Active reminders,
 * then flip still-Active past-due reminders to {@code OVERDUE}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderScheduler {

    private final ReminderRepository reminderRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedRate = 60_000)
    public void pollReminders() {
        fireDueReminders();
        flipOverdueReminders();
    }

    /** Pass 1 — fire one "reminder due" notification per Active, past-due, not-yet-notified reminder. */
    void fireDueReminders() {
        Instant now = Instant.now();
        List<Reminder> due = reminderRepository
                .findByStatusAndNotifiedFalseAndDueDateLessThanEqualAndDeletedAtIsNull(
                        ReminderStatus.Active, now);
        if (due.isEmpty()) {
            return;
        }

        int fired = 0;
        for (Reminder reminder : due) {
            try {
                TenantContext.setTenantId(reminder.getTenantId());
                publishDueNotification(reminder);

                // NotifyEventListener clears the context after fan-out — restore it so the
                // notified-flag flush passes TenantEntityListener's cross-tenant check.
                TenantContext.setTenantId(reminder.getTenantId());
                reminder.setNotified(true);
                reminderRepository.saveAndFlush(reminder);
                fired++;
            } catch (Exception e) {
                log.error("Failed to fire reminder {}: {}", reminder.getId(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Reminder scheduler fired {} of {} due reminder(s)", fired, due.size());
    }

    /** Pass 2 — flip still-Active, past-due reminders to OVERDUE. */
    void flipOverdueReminders() {
        Instant now = Instant.now();
        List<Reminder> stale = reminderRepository
                .findByStatusAndDueDateLessThanAndDeletedAtIsNull(ReminderStatus.Active, now);
        if (stale.isEmpty()) {
            return;
        }

        int flipped = 0;
        for (Reminder reminder : stale) {
            try {
                TenantContext.setTenantId(reminder.getTenantId());
                reminder.setStatus(ReminderStatus.OVERDUE);
                reminderRepository.saveAndFlush(reminder);
                flipped++;
            } catch (Exception e) {
                log.error("Failed to flip reminder {} to OVERDUE: {}", reminder.getId(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Reminder scheduler flipped {} reminder(s) to OVERDUE", flipped);
    }

    private void publishDueNotification(Reminder reminder) {
        if (reminder.getOwnerUserId() == null) {
            log.warn("Reminder {} has no ownerUserId — skipping notification", reminder.getId());
            return;
        }
        eventPublisher.publishEvent(NotifyEvent.builder()
                .type("REMINDER_DUE")
                .tenantId(reminder.getTenantId())
                .recipientUserIds(List.of(reminder.getOwnerUserId()))
                .title("Reminder due: " + reminder.getTitle())
                .message(reminder.getDescription() != null && !reminder.getDescription().isBlank()
                        ? reminder.getDescription()
                        : reminder.getTitle())
                .referenceType("REMINDER")
                .referencePublicId(reminder.getPublicId())
                .channels(Set.of(DeliveryChannel.IN_APP))
                .build());
    }
}