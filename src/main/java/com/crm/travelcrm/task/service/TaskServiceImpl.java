package com.crm.travelcrm.task.service;

import com.crm.travelcrm.auth.api.CurrentUserProvider;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.enums.LeadStageGroups;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.lead.service.LeadAccessGuard;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.permission.service.SubAgentScope;
import com.crm.travelcrm.task.dto.CreateTaskRequest;
import com.crm.travelcrm.task.dto.TaskResponse;
import com.crm.travelcrm.task.dto.TaskStatsDto;
import com.crm.travelcrm.task.dto.TaskWorkloadDto;
import com.crm.travelcrm.task.dto.UpdateTaskRequest;
import com.crm.travelcrm.task.entity.Task;
import com.crm.travelcrm.task.enums.TaskCategory;
import com.crm.travelcrm.task.enums.TaskPriority;
import com.crm.travelcrm.task.enums.TaskStatus;
import com.crm.travelcrm.task.mapper.TaskMapper;
import com.crm.travelcrm.task.repository.TaskRepository;
import com.crm.travelcrm.task.specification.TaskSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class TaskServiceImpl implements TaskService {

    private final TaskRepository taskRepository;
    private final TaskMapper taskMapper;
    private final UserRepository userRepository;
    private final LeadAccessGuard leadAccessGuard;
    private final LeadRepository leadRepository;
    private final SubAgentScope subAgentScope;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationEventPublisher eventPublisher;

    /** The "open" states used by overdue / My-Tasks / workload logic. */
    private static final List<TaskStatus> OPEN_STATUSES =
            List.of(TaskStatus.TODO, TaskStatus.IN_PROGRESS);

    private static final DateTimeFormatter LOG_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    // ── Commands ─────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public TaskResponse create(CreateTaskRequest request) {
        Long tenantId = requireTenantId();

        Task task = taskMapper.toEntity(request);
        if (task.getCategory() == null) task.setCategory(TaskCategory.GENERAL);
        if (task.getPriority() == null) task.setPriority(TaskPriority.MEDIUM);
        if (task.getStatus() == null)   task.setStatus(TaskStatus.TODO);
        task.setAllDay(Boolean.TRUE.equals(request.getAllDay()));

        task.setTenantId(tenantId);
        Long actorId = requireCurrentUserId();
        task.setOwnerUserId(actorId);

        applyReferences(task, request.getAssignToPublicId(), request.getLeadPublicId(), tenantId);

        if (task.getStatus() == TaskStatus.DONE) {
            task.setCompletedAt(Instant.now());
        }

        Task saved = taskRepository.save(task);
        notifyAssignee(saved, actorId);
        log.info("Task created | publicId: {} | tenantId: {} | assignee: {}",
                saved.getPublicId(), tenantId, saved.getAssignToUserId());
        return taskMapper.toDto(saved);
    }

    @Override
    @Transactional
    public TaskResponse update(UUID publicId, UpdateTaskRequest request) {
        Task task = findOrThrow(publicId);
        TaskStatus previousStatus = task.getStatus();
        Long previousAssignee = task.getAssignToUserId();

        taskMapper.updateEntity(request, task);
        if (request.getAllDay() != null) {
            task.setAllDay(request.getAllDay());
        }

        applyReferences(task, request.getAssignToPublicId(), request.getLeadPublicId(), task.getTenantId());

        syncCompletedAt(task, previousStatus);

        Task saved = taskRepository.save(task);

        // Re-notify only when the assignee actually changed to someone new.
        if (request.getAssignToPublicId() != null
                && saved.getAssignToUserId() != null
                && !saved.getAssignToUserId().equals(previousAssignee)) {
            notifyAssignee(saved, requireCurrentUserId());
        }
        log.info("Task updated | publicId: {} | tenantId: {}", publicId, saved.getTenantId());
        return taskMapper.toDto(saved);
    }

    @Override
    @Transactional
    public void delete(UUID publicId) {
        Task task = findOrThrow(publicId);
        task.softDelete(currentUsername());
        taskRepository.save(task);
        log.info("Task soft-deleted | publicId: {} | tenantId: {}", publicId, task.getTenantId());
    }

    @Override
    @Transactional
    public TaskResponse changeStatus(UUID publicId, TaskStatus status) {
        Task task = findOrThrow(publicId);
        TaskStatus previousStatus = task.getStatus();
        task.setStatus(status);
        syncCompletedAt(task, previousStatus);
        Task saved = taskRepository.save(task);
        log.info("Task status changed | publicId: {} | {} -> {}", publicId, previousStatus, status);
        return taskMapper.toDto(saved);
    }

    @Override
    @Transactional
    public TaskResponse markComplete(UUID publicId) {
        return changeStatus(publicId, TaskStatus.DONE);
    }

    @Override
    @Transactional
    public TaskResponse addLog(UUID publicId, String logText) {
        Task task = findOrThrow(publicId);
        String stamped = LOG_TS.format(Instant.now()) + " — " + logText.trim();
        task.getLogs().add(stamped);
        Task saved = taskRepository.save(task);
        log.info("Task log added | publicId: {}", publicId);
        return taskMapper.toDto(saved);
    }

    // ── Queries ──────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public TaskResponse getById(UUID publicId) {
        return taskMapper.toDto(findOrThrow(publicId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskResponse> list(String status, String priority, String category,
                                   UUID assigneePublicId, Instant from, Instant to) {
        Long tenantId = requireTenantId();
        Long assigneeUserId = resolveAssigneeFilter(assigneePublicId, tenantId);

        Specification<Task> spec = TaskSpecification.build(
                tenantId, parseStatus(status), parsePriority(priority), parseCategory(category),
                assigneeUserId, from, to);

        Long ownerFilter = subAgentScope.ownerFilter();
        if (ownerFilter != null) {
            spec = spec.and(TaskSpecification.ownedBy(ownerFilter));
        }

        return taskRepository.findAll(spec, Sort.by(Sort.Direction.ASC, "dueDate"))
                .stream().map(taskMapper::toListDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskResponse> getMyTasks() {
        Long tenantId = requireTenantId();
        Long me = requireCurrentUserId();
        // Sub-agent row scope: My Tasks stays confined to rows the caller OWNS (consistent with the
        // calendar 'mine' feed and findOrThrow); a no-op for every other role. Without this a
        // manager-owned task merely assigned to a sub-agent would leak through /my.
        Long ownerFilter = subAgentScope.ownerFilter();
        return taskRepository
                .findByTenantIdAndAssignToUserIdAndStatusInAndDeletedAtIsNull(tenantId, me, OPEN_STATUSES)
                .stream()
                .filter(t -> ownerFilter == null || ownerFilter.equals(t.getOwnerUserId()))
                .sorted(myTasksOrder())
                .map(taskMapper::toListDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TaskStatsDto getStats() {
        Long tenantId = requireTenantId();
        Instant now = Instant.now();
        Instant startOfToday = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant startOfTomorrow = startOfToday.plusSeconds(86_400);

        return TaskStatsDto.builder()
                .total(taskRepository.countByTenantIdAndDeletedAtIsNull(tenantId))
                .todo(taskRepository.countByTenantIdAndStatusAndDeletedAtIsNull(tenantId, TaskStatus.TODO))
                .inProgress(taskRepository.countByTenantIdAndStatusAndDeletedAtIsNull(tenantId, TaskStatus.IN_PROGRESS))
                .done(taskRepository.countByTenantIdAndStatusAndDeletedAtIsNull(tenantId, TaskStatus.DONE))
                .cancelled(taskRepository.countByTenantIdAndStatusAndDeletedAtIsNull(tenantId, TaskStatus.CANCELLED))
                .overdue(taskRepository.countOverdue(tenantId, OPEN_STATUSES, now))
                .dueToday(taskRepository.countDueBetween(tenantId, OPEN_STATUSES, startOfToday, startOfTomorrow))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskWorkloadDto> getWorkload() {
        Long tenantId = requireTenantId();
        // All live tasks except CANCELLED (logs are LAZY, so no N+1 from this fetch).
        List<Task> tasks = taskRepository
                .findByTenantIdAndStatusNotAndDeletedAtIsNull(tenantId, TaskStatus.CANCELLED);

        // Group tasks by assignee (null key = the "Unassigned" bucket), preserving first-seen order.
        Map<Long, Workload> byUser = new LinkedHashMap<>();
        for (Task t : tasks) {
            Workload w = byUser.computeIfAbsent(t.getAssignToUserId(),
                    k -> new Workload(t.getAssignToPublicId(), t.getAssignToName()));
            w.total++;
            switch (t.getStatus()) {
                case TODO -> w.todo++;
                case IN_PROGRESS -> w.inProgress++;
                case DONE -> w.done++;
                case CANCELLED -> { /* excluded from the fetch */ }
            }
            if (t.isOverdue()) w.overdue++;
        }

        // Merge in each user's ACTIVE-lead count (the same open-pipeline metric the load-based lead
        // assignment balances on), keyed by publicId, via ONE tenant-scoped GROUP BY (no N+1). A user
        // who owns active leads but has no tasks is ADDED so the workload reflects their real load; the
        // "Unassigned" task bucket (null publicId) has no lead counterpart.
        Map<UUID, Workload> byPublicId = new HashMap<>();
        for (Workload w : byUser.values()) {
            if (w.publicId != null) byPublicId.put(w.publicId, w);
        }
        List<Workload> leadOnly = new ArrayList<>();
        for (Object[] row : leadRepository.findActiveLeadWorkloadPerUser(
                tenantId, LeadStageGroups.ACTIVE_STAGES)) {
            UUID publicId   = (UUID) row[0];
            String name     = (String) row[1];
            long activeLeads = (Long) row[2];
            Workload w = byPublicId.get(publicId);
            if (w == null) {                       // owns active leads but has no tasks — add them
                w = new Workload(publicId, name);
                byPublicId.put(publicId, w);
                leadOnly.add(w);
            }
            w.activeLeads = activeLeads;
        }

        List<Workload> all = new ArrayList<>(byUser.values());
        all.addAll(leadOnly);

        return all.stream()
                // Busiest overall first: tasks + active leads.
                .sorted(Comparator.comparingLong((Workload w) -> w.total + w.activeLeads).reversed())
                .map(w -> TaskWorkloadDto.builder()
                        .assigneePublicId(w.publicId)
                        .assigneeName(w.publicId == null ? "Unassigned"
                                : (w.name != null ? w.name : "Unknown"))
                        .todo(w.todo)
                        .inProgress(w.inProgress)
                        .done(w.done)
                        .overdue(w.overdue)
                        .total(w.total)
                        .activeLeads(w.activeLeads)
                        .build())
                .toList();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────

    /** Mutable per-assignee accumulator for {@link #getWorkload()}. */
    private static final class Workload {
        final UUID publicId;
        final String name;
        long todo, inProgress, done, overdue, total, activeLeads;
        Workload(UUID publicId, String name) {
            this.publicId = publicId;
            this.name = name;
        }
    }

    private Comparator<Task> myTasksOrder() {
        // Highest priority first; then earliest calendar anchor (nulls last); then newest.
        return Comparator
                .comparingInt((Task t) -> t.getPriority() == null ? -1 : t.getPriority().weight()).reversed()
                .thenComparing(Task::calendarInstant, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Task::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    /** Stamp/clear {@code completedAt} when the task crosses into / out of DONE. */
    private void syncCompletedAt(Task task, TaskStatus previousStatus) {
        if (task.getStatus() == TaskStatus.DONE && previousStatus != TaskStatus.DONE) {
            task.setCompletedAt(Instant.now());
        } else if (task.getStatus() != TaskStatus.DONE) {
            task.setCompletedAt(null);
        }
    }

    private Task findOrThrow(UUID publicId) {
        Long tenantId = requireTenantId();
        Task task = taskRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + publicId));
        // Sub-agent row scope: 404 if a sub-agent doesn't own it (no-op for others). Single by-id
        // chokepoint — covers getById/update/delete/changeStatus/markComplete/addLog.
        subAgentScope.assertVisible(task, publicId);
        return task;
    }

    /**
     * Resolves the supplied assignee/lead publicIds to internal Long FKs, validating each within the
     * current tenant (assignee) and the caller's row scope (lead, via {@link LeadAccessGuard}).
     * Denormalizes display snapshots so board/calendar list views never need an extra query.
     * No-op for null publicIds, so a partial update leaves the existing reference untouched.
     */
    private void applyReferences(Task task, UUID assignToPublicId, UUID leadPublicId, Long tenantId) {
        if (assignToPublicId != null) {
            User assignee = userRepository
                    .findByPublicIdAndTenantIdAndDeletedAtIsNull(assignToPublicId, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found: " + assignToPublicId));
            task.setAssignToUserId(assignee.getId());
            task.setAssignToPublicId(assignee.getPublicId());
            task.setAssignToName(assignee.getName());
        }
        if (leadPublicId != null) {
            Lead lead = leadAccessGuard.requireVisible(leadPublicId, "LEAD_READ");
            task.setLeadRefId(lead.getId());
            task.setLeadPublicId(lead.getPublicId());
            task.setLeadName(lead.getCustomerName());
        }
    }

    /** Resolve an assignee-filter publicId to its internal id; throws 404 if it isn't a tenant user. */
    private Long resolveAssigneeFilter(UUID assigneePublicId, Long tenantId) {
        if (assigneePublicId == null) return null;
        return userRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(assigneePublicId, tenantId)
                .map(User::getId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + assigneePublicId));
    }

    private void notifyAssignee(Task task, Long actorId) {
        Long assignee = task.getAssignToUserId();
        // Don't notify an unassigned task, and don't self-notify when you assign a task to yourself.
        if (assignee == null || assignee.equals(actorId)) return;
        eventPublisher.publishEvent(NotifyEvent.builder()
                .type("TASK_ASSIGNED")
                .tenantId(task.getTenantId())
                .recipientUserIds(List.of(assignee))
                .title("Task assigned to you")
                .message("Task '" + task.getTitle() + "' was assigned to you")
                .referenceType("TASK")
                .referencePublicId(task.getPublicId())
                .channels(java.util.Set.of(DeliveryChannel.IN_APP))
                .build());
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty — no tenant bound to this request");
        }
        return tenantId;
    }

    private Long requireCurrentUserId() {
        Long id = currentUserProvider.currentUserIdOrNull();
        if (id == null) {
            throw new IllegalStateException("Tasks are available to tenant users only");
        }
        return id;
    }

    private String currentUsername() {
        return currentUserProvider.currentUsernameOrSystem();
    }

    private TaskStatus parseStatus(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return TaskStatus.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private TaskPriority parsePriority(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return TaskPriority.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private TaskCategory parseCategory(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return TaskCategory.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}