package com.crm.travelcrm.task.scheduler;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.settings.entity.TenantSettings;
import com.crm.travelcrm.settings.repository.TenantSettingsRepository;
import com.crm.travelcrm.task.entity.Task;
import com.crm.travelcrm.task.enums.TaskStatus;
import com.crm.travelcrm.task.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Alerts the ASSIGNED agent when a task goes overdue.
 *
 * <p>Nothing scanned tasks before this — {@code TaskServiceImpl} publishes only on assignment, so a
 * task could sit past its due date indefinitely without anyone being told. Modelled on
 * {@code ReminderScheduler}, with three deliberate differences, each of which was a defect there:
 *
 * <ol>
 *   <li><b>Notifies the assignee, not the creator.</b> {@code ReminderScheduler} publishes to
 *       {@code ownerUserId}, so a manager who creates work for an agent gets the reminder and the
 *       agent does not. The whole point of this feature is telling the person who has to act.</li>
 *   <li><b>A timestamp, not a boolean.</b> {@code Reminder.notified} cannot express "alerted at T",
 *       so an escalation policy would need a migration. {@code overdue_notified_at} is cleared by
 *       the service whenever a task leaves the overdue state, so a re-scheduled or re-opened task
 *       becomes eligible again — a boolean set once would silence it permanently.</li>
 *   <li><b>Bounded per tick.</b> The poll takes a page, not the whole result set. A tenant that
 *       imports a thousand back-dated tasks would otherwise produce a thousand notifications, three
 *       WhatsApp messages a second, in one tick.</li>
 * </ol>
 *
 * <h2>Tenant context</h2>
 * Runs on a scheduler thread with no request and therefore no {@code TenantContext}: the poll query
 * deliberately spans all tenants, and isolation is re-applied per row before publishing and before
 * the marker flush, then cleared in {@code finally}. Note the context must be re-set after
 * publishing — {@code NotifyEventListener} save/restores it, but the flush needs it bound for
 * {@code TenantEntityListener}'s cross-tenant check.
 *
 * <h2>Channels</h2>
 * IN_APP always. WhatsApp and email only when the tenant has switched them on
 * ({@code tenant_settings.task_overdue_alert_whatsapp} / {@code _email}), both default false.
 * There is no per-user opt-out anywhere in this product yet, so a tenant-level switch is the only
 * off-ramp an agency has — see docs/ALL_TASKS_MODULE_DESIGN.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskOverdueScanner {

    private final TaskRepository taskRepository;
    private final TenantSettingsRepository tenantSettingsRepository;
    private final ApplicationEventPublisher eventPublisher;

    private static final Set<TaskStatus> OPEN_STATUSES =
            EnumSet.of(TaskStatus.TODO, TaskStatus.IN_PROGRESS);

    /** Ceiling on alerts fired per tick — see the class javadoc on back-dated imports. */
    @Value("${app.task.overdue-scan.batch-size:100}")
    private int batchSize;

    @Value("${app.task.overdue-scan.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedRateString = "${app.task.overdue-scan.interval-ms:60000}")
    public void scanOverdueTasks() {
        if (!enabled) {
            return;
        }

        List<Task> overdue = taskRepository.findOverdueAwaitingAlert(
                OPEN_STATUSES, Instant.now(), PageRequest.of(0, Math.max(batchSize, 1)));
        if (overdue.isEmpty()) {
            return;
        }

        // One settings lookup per TENANT, not per task: a tenant with 80 overdue tasks in this batch
        // would otherwise cost 80 identical reads.
        Map<Long, Set<DeliveryChannel>> channelsByTenant = new HashMap<>();

        int fired = 0;
        for (Task task : overdue) {
            try {
                TenantContext.setTenantId(task.getTenantId());
                Set<DeliveryChannel> channels = channelsByTenant.computeIfAbsent(
                        task.getTenantId(), this::resolveChannels);

                publishOverdue(task, channels);

                // Re-bind: the listener save/restores the context around fan-out, and the marker
                // flush below must run with the tenant bound or TenantEntityListener rejects it.
                TenantContext.setTenantId(task.getTenantId());
                task.setOverdueNotifiedAt(Instant.now());
                taskRepository.saveAndFlush(task);
                fired++;
            } catch (Exception e) {
                // Per-row catch: one bad task (or one unreachable provider) must not stop the sweep.
                log.error("Failed to fire overdue alert for task {}: {}",
                        task.getPublicId(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Task overdue scanner fired {} of {} alert(s)", fired, overdue.size());
    }

    /** IN_APP always; the two interrupting channels only when the tenant opted in. */
    private Set<DeliveryChannel> resolveChannels(Long tenantId) {
        Set<DeliveryChannel> channels = EnumSet.of(DeliveryChannel.IN_APP);
        TenantSettings settings = tenantSettingsRepository.findByTenantId(tenantId).orElse(null);
        if (settings == null) {
            return channels;
        }
        if (settings.isTaskOverdueAlertWhatsApp()) {
            channels.add(DeliveryChannel.WHATSAPP);
        }
        if (settings.isTaskOverdueAlertEmail()) {
            channels.add(DeliveryChannel.EMAIL);
        }
        return channels;
    }

    private void publishOverdue(Task task, Set<DeliveryChannel> channels) {
        eventPublisher.publishEvent(NotifyEvent.builder()
                .type("TASK_OVERDUE")
                .tenantId(task.getTenantId())
                // Explicit and never empty — the query already excludes unassigned tasks. Leaving
                // this null would make the in-app channel fall back to notifying tenant admins,
                // which is the wrong audience for "your task is overdue".
                .recipientUserIds(List.of(task.getAssignToUserId()))
                .title("Task overdue: " + task.getTitle())
                .message(buildMessage(task))
                .referenceType("TASK")
                .referencePublicId(task.getPublicId())
                .channels(channels)
                .build());
    }

    /** Enough context to act without opening the CRM — which trip, which guest, what is wanted. */
    private String buildMessage(Task task) {
        StringBuilder sb = new StringBuilder();
        sb.append(task.getDescription() != null && !task.getDescription().isBlank()
                ? task.getDescription().trim()
                : task.getTitle());
        if (task.getBookingCode() != null) {
            sb.append(" · Trip ").append(task.getBookingCode());
        }
        if (task.getCustomerNameSnapshot() != null) {
            sb.append(" · ").append(task.getCustomerNameSnapshot());
        }
        return sb.toString();
    }
}
