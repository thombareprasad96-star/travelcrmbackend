package com.crm.travelcrm.workload;

import com.crm.travelcrm.lead.enums.LeadStageGroups;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.reminder.entity.ReminderStatus;
import com.crm.travelcrm.reminder.repository.ReminderRepository;
import com.crm.travelcrm.task.enums.TaskStatus;
import com.crm.travelcrm.task.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single source of truth for "how busy is this person".
 *
 * <p><b>Why this is its own module.</b> Two consumers must agree exactly: the load-based lead
 * assignment (which decides who gets a new lead) and the calendar's workload tab (which shows a
 * manager who is busy). When they disagree, the product is lying to the user — the manager sees one
 * ordering and leads flow according to another, and nobody can explain why. Neither module can own
 * the metric: it spans leads, tasks and reminders, and {@code lead} owning a query over {@code task}
 * (or vice-versa) inverts the dependency the other way. {@code calendar} already aggregates all
 * three, but {@code lead.assignment} cannot depend on {@code calendar} — {@code calendar} depends on
 * {@code lead}.
 *
 * <p>The formula itself lives on {@link UserWorkload#score()}, not here, so it is stated once and
 * read by both.
 */
@Service
@RequiredArgsConstructor
public class WorkloadService {

    private final LeadRepository leadRepository;
    private final TaskRepository taskRepository;
    private final ReminderRepository reminderRepository;

    /**
     * Tasks that still represent work. DONE and CANCELLED are excluded: the metric measures what is
     * outstanding, not throughput.
     */
    public static final Set<TaskStatus> OPEN_TASK_STATUSES =
            Set.of(TaskStatus.TODO, TaskStatus.IN_PROGRESS);

    /**
     * Reminders that still represent work. {@code Snoozed} is excluded — an explicit "not now" from
     * someone triaging their queue should not count against them. Completed/Dismissed are done.
     */
    public static final Set<ReminderStatus> OPEN_REMINDER_STATUSES =
            Set.of(ReminderStatus.Active, ReminderStatus.OVERDUE);

    /**
     * Workload for every user in {@code userIds}, <b>zero-filled</b>.
     *
     * <p>Zero-filling is load-bearing, not tidiness: the assignment engine picks the minimum, so a
     * user absent from the map because they have nothing to do would be silently skipped — the
     * emptiest person would never be assigned anything, which is the exact opposite of the intent.
     *
     * <p>Three GROUP BY queries total regardless of pool size; no N+1.
     *
     * @param tenantId the tenant — passed explicitly rather than read from {@code TenantContext}, so
     *                 this is callable from the machine ingest path where there is no request thread
     *                 to have populated it
     */
    @Transactional(readOnly = true)
    public Map<Long, UserWorkload> workloadFor(Long tenantId, Collection<Long> userIds) {
        Map<Long, long[]> acc = new HashMap<>();   // [todo, inProgress, activeLeads, openReminders]
        for (Long id : userIds) {
            acc.put(id, new long[4]);
        }
        if (userIds.isEmpty()) {
            return Map.of();
        }

        for (Object[] row : taskRepository.countTasksPerUserByStatus(
                tenantId, userIds, OPEN_TASK_STATUSES)) {
            long[] a = acc.get((Long) row[0]);
            if (a == null) continue;              // task assigned outside the requested pool
            TaskStatus status = (TaskStatus) row[1];
            long count = (Long) row[2];
            if (status == TaskStatus.TODO) a[0] = count;
            else if (status == TaskStatus.IN_PROGRESS) a[1] = count;
        }

        for (Object[] row : leadRepository.countActiveLeadsPerUser(
                tenantId, userIds, LeadStageGroups.ACTIVE_STAGES)) {
            long[] a = acc.get((Long) row[0]);
            if (a != null) a[2] = (Long) row[1];
        }

        for (Object[] row : reminderRepository.countOpenRemindersPerUser(
                tenantId, userIds, OPEN_REMINDER_STATUSES)) {
            long[] a = acc.get((Long) row[0]);
            if (a != null) a[3] = (Long) row[1];
        }

        Map<Long, UserWorkload> out = new HashMap<>();
        acc.forEach((id, a) -> out.put(id, new UserWorkload(id, a[0], a[1], a[2], a[3])));
        return out;
    }

    /** {@link #workloadFor} reduced to just the scores — what the assignment strategies consume. */
    @Transactional(readOnly = true)
    public Map<Long, Long> scoresFor(Long tenantId, Collection<Long> userIds) {
        Map<Long, Long> scores = new HashMap<>();
        workloadFor(tenantId, userIds).forEach((id, w) -> scores.put(id, w.score()));
        return scores;
    }

    /** Convenience for callers holding a {@link List} of ids and wanting a stable iteration order. */
    @Transactional(readOnly = true)
    public List<UserWorkload> workloadList(Long tenantId, List<Long> userIds) {
        Map<Long, UserWorkload> map = workloadFor(tenantId, userIds);
        return userIds.stream()
                .map(id -> map.getOrDefault(id, UserWorkload.empty(id)))
                .toList();
    }
}
