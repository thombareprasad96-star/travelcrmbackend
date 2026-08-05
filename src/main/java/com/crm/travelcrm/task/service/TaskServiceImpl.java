package com.crm.travelcrm.task.service;

import com.crm.travelcrm.auth.api.CurrentUserProvider;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.context.TenantTimeZone;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.enums.LeadStageGroups;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.lead.service.LeadAccessGuard;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.permission.enums.Permission;
import com.crm.travelcrm.permission.service.SubAgentScope;
import com.crm.travelcrm.task.dto.CreateTaskRequest;
import com.crm.travelcrm.task.dto.TaskResponse;
import com.crm.travelcrm.task.dto.TaskStatsDto;
import com.crm.travelcrm.task.dto.TaskTabCountsDto;
import com.crm.travelcrm.task.dto.TaskWorkloadDto;
import com.crm.travelcrm.task.dto.UpdateTaskRequest;
import com.crm.travelcrm.task.entity.Task;
import com.crm.travelcrm.task.enums.TaskCategory;
import com.crm.travelcrm.task.enums.TaskPriority;
import com.crm.travelcrm.task.enums.TaskStatus;
import com.crm.travelcrm.task.enums.TaskTab;
import com.crm.travelcrm.task.mapper.TaskMapper;
import com.crm.travelcrm.reminder.repository.ReminderRepository;
import com.crm.travelcrm.task.repository.TaskRepository;
import com.crm.travelcrm.task.specification.TaskSpecification;
import com.crm.travelcrm.workload.UserWorkload;
import com.crm.travelcrm.workload.WorkloadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final BookingRepository bookingRepository;
    private final ReminderRepository reminderRepository;
    private final SubAgentScope subAgentScope;
    private final TaskAccessGuard accessGuard;
    private final TenantTimeZone tenantTimeZone;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationEventPublisher eventPublisher;

    /** The "open" states used by overdue / My-Tasks / workload logic. */
    private static final List<TaskStatus> OPEN_STATUSES =
            List.of(TaskStatus.TODO, TaskStatus.IN_PROGRESS);

    private static final DateTimeFormatter LOG_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    // ── All Tasks paging ─────────────────────────────────────────────────────────────────────

    private static final int DEFAULT_PAGE_SIZE = 25;

    /**
     * Hard ceiling on {@code ?size=}. Not cosmetic: without it {@code ?size=1000000} pulls a
     * tenant's entire task history into one heap on a box sized for a pilot.
     */
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * Sortable columns, allow-listed.
     *
     * <p>{@code Sort.by(rawParam)} — what the lead and booking controllers do — turns
     * {@code ?sortBy=garbage} into a 500 {@code PropertyReferenceException} straight out of
     * Hibernate. The keys are the wire names the grid sends; the values are entity properties.
     *
     * <p>There is deliberately no "anchor" sort: {@code COALESCE(start_at, due_date)} is not a
     * mapped property, so it cannot be expressed through {@code Sort}. The grid sorts on
     * {@code dueDate}, which is the column it renders.
     */
    private static final Map<String, String> SORTABLE = Map.of(
            "dueDate",     "dueDate",
            "dueAt",       "dueDate",
            "startAt",     "startAt",
            "createdAt",   "createdAt",
            "title",       "title",
            "priority",    "priority",
            "status",      "status",
            "assignee",    "assignToName",
            "bookingCode", "bookingCode",
            "guest",       "customerNameSnapshot");

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
        // "Created By" snapshot. BaseEntity.createdBy holds only a login username, which is not
        // joinable to a user and renders as nothing useful in a grid; this mirrors assignToName.
        task.setOwnerName(userRepository.findById(actorId).map(User::getName).orElse(null));

        applyReferences(task, request.getAssignToPublicId(), request.getLeadPublicId(),
                request.getBookingPublicId(), tenantId);

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

        // Before applyReferences: an unlink + a new link in the same body must end up linked to the
        // new trip, not depend on which branch ran last.
        if (Boolean.TRUE.equals(request.getClearBookingLink())) {
            clearBookingLink(task);
        }

        applyReferences(task, request.getAssignToPublicId(), request.getLeadPublicId(),
                request.getBookingPublicId(), task.getTenantId());

        syncCompletedAt(task, previousStatus);
        // A task that moves out of the overdue window (re-scheduled, re-opened, completed) must be
        // eligible to alert again if it falls back in; see TaskOverdueScanner.
        clearOverdueMarkIfNoLongerOverdue(task);

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
        clearOverdueMarkIfNoLongerOverdue(task);
        Task saved = taskRepository.save(task);

        // Tell the assignee their task was closed by someone else. Nothing was published here
        // before, so a manager completing an agent's task was silent.
        if (status == TaskStatus.DONE && previousStatus != TaskStatus.DONE) {
            notifyCompletion(saved, requireCurrentUserId());
        }
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

    // ── All Tasks screen ─────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<TaskResponse> listPaged(TaskTab tab, String search, UUID assigneePublicId,
                                        String status, String priority, String category,
                                        int page, int size, String sortBy, String sortDir) {
        Long tenantId = accessGuard.requireTenantId();
        Set<Long> visible = accessGuard.visibleAssigneeIds(Permission.TASK_READ.key());
        Pageable pageable = pageable(page, size, sortBy, sortDir);

        // Scope NONE ⇒ an EMPTY page, never an unfiltered one. Returning everything on an empty
        // visibility set is the classic inversion of this check.
        if (visible != null && visible.isEmpty()) {
            return Page.empty(pageable);
        }

        Specification<Task> spec = scopedSpec(
                tenantId, visible, tab, search, assigneePublicId, status, priority, category);

        return taskRepository.findAll(spec, pageable).map(taskMapper::toListDto);
    }

    @Override
    @Transactional(readOnly = true)
    public TaskTabCountsDto tabCounts(String search, UUID assigneePublicId,
                                      String status, String priority, String category) {
        Long tenantId = accessGuard.requireTenantId();
        Set<Long> visible = accessGuard.visibleAssigneeIds(Permission.TASK_READ.key());

        if (visible != null && visible.isEmpty()) {
            return TaskTabCountsDto.builder().build();
        }

        // One count per tab, each through the SAME scopedSpec the list uses — that identity is the
        // whole point: a badge that disagrees with the list it opens is worse than no badge.
        return TaskTabCountsDto.builder()
                .today(count(tenantId, visible, TaskTab.TODAY, search, assigneePublicId, status, priority, category))
                .yesterday(count(tenantId, visible, TaskTab.YESTERDAY, search, assigneePublicId, status, priority, category))
                .overdue(count(tenantId, visible, TaskTab.OVERDUE, search, assigneePublicId, status, priority, category))
                .upcoming(count(tenantId, visible, TaskTab.UPCOMING, search, assigneePublicId, status, priority, category))
                .all(count(tenantId, visible, TaskTab.ALL, search, assigneePublicId, status, priority, category))
                .build();
    }

    private long count(Long tenantId, Set<Long> visible, TaskTab tab, String search,
                       UUID assigneePublicId, String status, String priority, String category) {
        return taskRepository.count(scopedSpec(
                tenantId, visible, tab, search, assigneePublicId, status, priority, category));
    }

    /**
     * Builds the one specification both the list and the counts consume.
     *
     * <p>Layers, in order: tenant + soft-delete (from {@code TaskSpecification.build}), the explicit
     * column filters, the tab's date/status window, free-text search, and finally BOTH row-scope
     * dimensions. The two scopes ask different questions and both must be asked — {@code SubAgentScope}
     * confines a B2B partner to tasks it OWNS, {@code ScopeResolver} confines everyone to tasks
     * ASSIGNED within their visibility.
     */
    private Specification<Task> scopedSpec(Long tenantId, Set<Long> visible, TaskTab tab,
                                           String search, UUID assigneePublicId,
                                           String status, String priority, String category) {
        Specification<Task> spec = TaskSpecification.build(
                tenantId,
                parseEnum(TaskStatus.class, status, "status"),
                parseEnum(TaskPriority.class, priority, "priority"),
                parseEnum(TaskCategory.class, category, "category"),
                resolveAssigneeFilter(assigneePublicId, tenantId),
                null, null);

        spec = spec.and(tabSpec(tab == null ? TaskTab.ALL : tab, tenantId));

        if (StringUtils.hasText(search)) {
            spec = spec.and(TaskSpecification.search(search));
        }

        Long ownerFilter = accessGuard.ownerFilter();
        if (ownerFilter != null) {
            spec = spec.and(TaskSpecification.ownedBy(ownerFilter));
        }
        if (visible != null) {
            spec = spec.and(TaskSpecification.assignedToAnyOrUnassigned(visible));
        }
        return spec;
    }

    /**
     * The tab's date window + status restriction, measured in the TENANT's timezone.
     *
     * <p>TODAY and OVERDUE overlap deliberately (an 11:00 task read at 14:00 is both) — that is what
     * the reference product shows, and separating them would make a task disappear from Today the
     * moment it slipped. ALL is the only tab that includes DONE/CANCELLED.
     */
    private Specification<Task> tabSpec(TaskTab tab, Long tenantId) {
        if (tab == TaskTab.ALL) {
            return (root, query, cb) -> cb.conjunction();
        }

        ZoneId zone = tenantTimeZone.forTenant(tenantId);
        LocalDate today = LocalDate.now(zone);
        Instant startOfToday = today.atStartOfDay(zone).toInstant();
        Instant startOfTomorrow = today.plusDays(1).atStartOfDay(zone).toInstant();
        Instant startOfYesterday = today.minusDays(1).atStartOfDay(zone).toInstant();

        Specification<Task> open = TaskSpecification.openOnly(OPEN_STATUSES);
        return switch (tab) {
            case TODAY     -> open.and(TaskSpecification.anchorBetween(startOfToday, startOfTomorrow));
            case YESTERDAY -> open.and(TaskSpecification.anchorBetween(startOfYesterday, startOfToday));
            case OVERDUE   -> open.and(TaskSpecification.anchorBefore(Instant.now()));
            case UPCOMING  -> open.and(TaskSpecification.anchorAtOrAfter(startOfTomorrow));
            case ALL       -> open;   // unreachable — handled above
        };
    }

    /** Page number, clamped size, and an allow-listed sort. */
    private Pageable pageable(int page, int size, String sortBy, String sortDir) {
        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir)
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        String property = SORTABLE.getOrDefault(
                sortBy == null ? "" : sortBy.trim(), "dueDate");
        return PageRequest.of(Math.max(page, 0), clampSize(size), Sort.by(direction, property));
    }

    private int clampSize(int requested) {
        if (requested <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    /**
     * Strict enum parse — 404s on an unknown value instead of silently ignoring the filter.
     *
     * <p>The lenient {@code parseStatus}/{@code parsePriority}/{@code parseCategory} helpers below
     * swallow a bad value and return null, so {@code ?status=OVERDUE} (a plausible tab name that is
     * not a TaskStatus) quietly returns EVERY task. They are kept for the legacy
     * {@code GET /api/tasks}, whose callers depend on that leniency; the new endpoints use this.
     */
    private <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String label) {
        if (!StringUtils.hasText(raw)) return null;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException("Unknown " + label + ": " + raw);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public TaskStatsDto getStats() {
        Long tenantId = requireTenantId();
        Instant now = Instant.now();
        // Day boundaries in the TENANT's zone, not UTC. This previously read
        // LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC), which for an IST agency began
        // "today" at 05:30 local — so an 18:00 task counted as due tomorrow. Same fix, same reason,
        // as LeadServiceImpl's stats summary.
        ZoneId zone = tenantTimeZone.forTenant(tenantId);
        Instant startOfToday = LocalDate.now(zone).atStartOfDay(zone).toInstant();
        Instant startOfTomorrow = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant();

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
                    k -> new Workload(k, t.getAssignToPublicId(), t.getAssignToName()));
            w.total++;
            switch (t.getStatus()) {
                case TODO -> w.todo++;
                case IN_PROGRESS -> w.inProgress++;
                case DONE -> w.done++;
                case CANCELLED -> { /* excluded from the fetch */ }
            }
            if (t.isOverdue()) w.overdue++;
        }

        // Merge in each user's ACTIVE-lead count, keyed by publicId, via ONE tenant-scoped GROUP BY
        // (no N+1). A user who owns active leads but has no tasks is ADDED so the workload reflects
        // their real load; the "Unassigned" task bucket (null publicId) has no lead counterpart.
        Map<UUID, Workload> byPublicId = new HashMap<>();
        for (Workload w : byUser.values()) {
            if (w.publicId != null) byPublicId.put(w.publicId, w);
        }
        List<Workload> leadOnly = new ArrayList<>();
        for (Object[] row : leadRepository.findActiveLeadWorkloadPerUser(
                tenantId, LeadStageGroups.ACTIVE_STAGES)) {
            Long userId      = (Long) row[0];
            UUID publicId    = (UUID) row[1];
            String name      = (String) row[2];
            long activeLeads = (Long) row[3];
            Workload w = byPublicId.get(publicId);
            if (w == null) {                       // owns active leads but has no tasks — add them
                w = new Workload(userId, publicId, name);
                byPublicId.put(publicId, w);
                leadOnly.add(w);
            }
            w.activeLeads = activeLeads;
        }

        List<Workload> all = new ArrayList<>(byUser.values());
        all.addAll(leadOnly);

        // Merge in OPEN reminders (Active + OVERDUE) — one more GROUP BY, through the same repository
        // method WorkloadService uses, so the number here and the number the auto-assignment balanced
        // on are computed by the same query rather than two that agree today and drift later.
        //
        // NOTE: this adjusts the SCORE of users already listed; it does not add rows. A user with ONLY
        // reminders (no tasks, no leads) still does not appear here — a pre-existing property of this
        // view, which is the union of task assignees and lead owners.
        List<Long> userIds = all.stream().map(w -> w.userId).filter(Objects::nonNull).toList();
        if (!userIds.isEmpty()) {
            Map<Long, Workload> byUserId = new HashMap<>();
            for (Workload w : all) {
                if (w.userId != null) byUserId.put(w.userId, w);
            }
            for (Object[] row : reminderRepository.countOpenRemindersPerUser(
                    tenantId, userIds, WorkloadService.OPEN_REMINDER_STATUSES)) {
                Workload w = byUserId.get((Long) row[0]);
                if (w != null) w.openReminders = (Long) row[1];
            }
        }

        return all.stream()
                // Busiest first, by the SHARED metric — todo + inProgress + activeLeads +
                // openReminders (UserWorkload.score()).
                //
                // This deliberately no longer sorts on `total + activeLeads`. `total` counts DONE
                // tasks, so finishing work made someone look busier and pushed them DOWN this list
                // while the load-based lead assignment — which never counted DONE — kept sending them
                // leads. The two views disagreed, and the more work an agent completed the more the
                // manager's screen misrepresented them. `done` and `overdue` are still reported,
                // because a manager wants to see them; they just do not decide the ordering.
                .sorted(Comparator.comparingLong((Workload w) ->
                        new UserWorkload(w.userId, w.todo, w.inProgress, w.activeLeads,
                                w.openReminders).score()).reversed())
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
                        .openReminders(w.openReminders)
                        .workloadScore(new UserWorkload(w.userId, w.todo, w.inProgress, w.activeLeads,
                                w.openReminders).score())
                        .build())
                .toList();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────

    /** Mutable per-assignee accumulator for {@link #getWorkload()}. */
    private static final class Workload {
        /** Internal id — needed to key the per-user reminder query. Null for the Unassigned bucket. */
        final Long userId;
        final UUID publicId;
        final String name;
        long todo, inProgress, done, overdue, total, activeLeads, openReminders;
        Workload(Long userId, UUID publicId, String name) {
            this.userId = userId;
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

    /**
     * Single by-id chokepoint — covers getById/update/delete/changeStatus/markComplete/addLog.
     * Delegates to {@link TaskAccessGuard} so the scope rule lives in exactly one place; it applies
     * BOTH the sub-agent owner restriction and the OWN/TEAM/ALL assignee scope, and 404s (never
     * 403s) on a miss.
     */
    private Task findOrThrow(UUID publicId) {
        return accessGuard.requireVisible(publicId, Permission.TASK_READ.key());
    }

    /**
     * Drop the "already alerted" mark once a task is no longer overdue, so a re-scheduled or
     * re-opened task alerts again when it next slips. Without this, moving a due date forward would
     * permanently silence that task.
     */
    private void clearOverdueMarkIfNoLongerOverdue(Task task) {
        if (task.getOverdueNotifiedAt() != null && !task.isOverdue()) {
            task.setOverdueNotifiedAt(null);
        }
    }

    /**
     * Resolves the supplied assignee/lead publicIds to internal Long FKs, validating each within the
     * current tenant (assignee) and the caller's row scope (lead, via {@link LeadAccessGuard}).
     * Denormalizes display snapshots so board/calendar list views never need an extra query.
     * No-op for null publicIds, so a partial update leaves the existing reference untouched.
     */
    private void applyReferences(Task task, UUID assignToPublicId, UUID leadPublicId,
                                 UUID bookingPublicId, Long tenantId) {
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
            // A directly-linked lead is the fallback source of truth for "Trip Source" when the task
            // has no booking; a booking link (below) overrides it, since the trip is the better answer.
            if (task.getBookingRefId() == null) {
                task.setTripSource(sourceOf(lead));
            }
        }
        if (bookingPublicId != null) {
            // Tenant-scoped finder, never bare findById — the Hibernate tenant filter does not apply
            // to EntityManager.find() and this is a cross-aggregate reference (see CLAUDE.md).
            Booking booking = bookingRepository
                    .findByPublicIdAndTenantIdAndDeletedAtIsNull(bookingPublicId, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingPublicId));
            task.setBookingRefId(booking.getId());
            task.setBookingPublicId(booking.getPublicId());
            task.setBookingCode(booking.getBookingCode());
            task.setCustomerNameSnapshot(booking.getCustomerNameSnapshot());
            task.setTripSource(sourceOfBooking(booking, tenantId));
        }
    }

    /**
     * Drop the booking link and every display snapshot that came with it.
     *
     * <p>{@link #applyReferences} is deliberately null-safe — a partial update must not wipe a
     * reference the caller simply omitted — so removing a link needs its own signal
     * ({@code UpdateTaskRequest.clearBookingLink}).
     *
     * <p>{@code tripSource} is NOT just nulled: it was resolved from the booking, but a task that
     * also has a lead link should fall back to that lead's source, which is exactly what
     * {@code applyReferences} would have stored had the task never had a booking.
     */
    private void clearBookingLink(Task task) {
        task.setBookingRefId(null);
        task.setBookingPublicId(null);
        task.setBookingCode(null);
        task.setCustomerNameSnapshot(null);
        task.setTripSource(task.getLeadRefId() == null ? null
                : leadRepository
                        .findByIdAndTenantIdAndDeletedAtIsNull(task.getLeadRefId(), task.getTenantId())
                        .map(this::sourceOf)
                        .orElse(null));
    }

    /**
     * "Trip Source" for a booking — the display name of the source lead's {@code LeadSource}.
     *
     * <p>A booking made directly (walk-in, no lead) has no {@code leadId} and therefore no source;
     * that is a real null, not an error, and the grid renders a dash. Resolved once at link time and
     * snapshotted, so the grid never joins and the value survives the lead being deleted.
     */
    private String sourceOfBooking(Booking booking, Long tenantId) {
        if (booking.getLeadId() == null) return null;
        return leadRepository.findByIdAndTenantIdAndDeletedAtIsNull(booking.getLeadId(), tenantId)
                .map(this::sourceOf)
                .orElse(null);
    }

    /** {@code LeadSource.getDisplayName()} ("WhatsApp", "Direct Call", …) — the wire format the FE uses. */
    private String sourceOf(Lead lead) {
        return lead.getLeadSource() == null ? null : lead.getLeadSource().getDisplayName();
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

    /** Tell the assignee their task was completed by somebody else. Silent when they did it themselves. */
    private void notifyCompletion(Task task, Long actorId) {
        Long assignee = task.getAssignToUserId();
        if (assignee == null || assignee.equals(actorId)) return;
        eventPublisher.publishEvent(NotifyEvent.builder()
                .type("TASK_COMPLETED")
                .tenantId(task.getTenantId())
                .recipientUserIds(List.of(assignee))
                .title("Task completed")
                .message("Task '" + task.getTitle() + "' was marked complete")
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