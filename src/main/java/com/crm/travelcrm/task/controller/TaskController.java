package com.crm.travelcrm.task.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.task.dto.AddTaskLogRequest;
import com.crm.travelcrm.task.dto.CreateTaskRequest;
import com.crm.travelcrm.task.dto.TaskResponse;
import com.crm.travelcrm.task.dto.TaskStatsDto;
import com.crm.travelcrm.task.dto.TaskTabCountsDto;
import com.crm.travelcrm.task.dto.UpdateTaskRequest;
import com.crm.travelcrm.task.dto.UpdateTaskStatusRequest;
import com.crm.travelcrm.task.dto.TaskWorkloadDto;
import com.crm.travelcrm.task.enums.TaskTab;
import com.crm.travelcrm.task.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Task board + team-workload REST API.
 *
 * <p>Responses are intentionally <b>not</b> wrapped in {@code ApiResponse} — the task/calendar
 * frontend reads {@code res.data} directly as the object/array, mirroring the sibling reminder
 * module (which the calendar aggregates alongside). Path variables are the external
 * {@code publicId} (UUID), never the internal Long id.
 *
 * <p>Access is gated by fine-grained {@code TASK_*} authorities; the tenant-wide aggregates
 * ({@code /stats}, {@code /workload}) additionally require {@code CRM_FULL}, which blocks sub-agents
 * (they get no tenant-wide roll-up). Per-user visibility (sub-agent row scope) is enforced in the
 * service. All routes fall under {@code SecurityConfig}'s {@code anyRequest().authenticated()} rule.
 */
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('TASK_READ')")   // class default; mutating methods override below
public class TaskController {

    private final TaskService taskService;

    @PostMapping
    @PreAuthorize("hasAuthority('TASK_CREATE')")
    public ResponseEntity<TaskResponse> create(@Valid @RequestBody CreateTaskRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(taskService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<TaskResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) UUID assignee,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(taskService.list(
                status, priority, category, assignee, parseInstant(from), parseInstant(to)));
    }

    @GetMapping("/my")
    public ResponseEntity<List<TaskResponse>> myTasks() {
        return ResponseEntity.ok(taskService.getMyTasks());
    }

    /**
     * The All Tasks grid — paged, row-scoped, one tab at a time.
     *
     * <p>Deliberately a SEPARATE endpoint from {@code GET /api/tasks}: that one returns a bare
     * unpaginated array which the existing calendar/board frontend reads as {@code res.data}, and
     * paginating it would break that contract. This one uses the standard {@code PagedApiResponse}
     * envelope, so it is the shape every new list screen should copy.
     *
     * <p>Gated on {@code TASK_READ} only — unlike {@code /stats} and {@code /workload}, which are
     * tenant-wide roll-ups behind {@code CRM_FULL}. This list is row-scoped per caller, so a
     * sub-agent legitimately sees their own slice of it.
     */
    @GetMapping("/list")
    public ResponseEntity<PagedApiResponse<TaskResponse>> listPaged(
            @RequestParam(required = false, defaultValue = "ALL") String tab,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID assignee,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "dueDate") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir) {

        Page<TaskResponse> result = taskService.listPaged(
                parseTab(tab), q, assignee, status, priority, category, page, size, sortBy, sortDir);

        return ResponseEntity.ok(PagedApiResponse.of(
                "Tasks fetched successfully",
                result.getContent(),
                PaginationMeta.from(result, sortBy, sortDir)));
    }

    /**
     * Badge counts for the five left-rail tabs. Honours every filter except {@code tab} itself, and
     * is computed from the same scoped specification {@link #listPaged} uses.
     */
    @GetMapping("/tab-counts")
    public ResponseEntity<ApiResponse<TaskTabCountsDto>> tabCounts(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID assignee,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String category) {
        return ResponseEntity.ok(ApiResponse.success(
                "Task tab counts fetched successfully",
                taskService.tabCounts(q, assignee, status, priority, category)));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('CRM_FULL')")   // tenant-wide aggregate — blocks sub-agents (no CRM_FULL)
    public ResponseEntity<TaskStatsDto> stats() {
        return ResponseEntity.ok(taskService.getStats());
    }

    @GetMapping("/workload")
    @PreAuthorize("hasAuthority('CRM_FULL')")   // tenant-wide team roll-up — blocks sub-agents
    public ResponseEntity<List<TaskWorkloadDto>> workload() {
        return ResponseEntity.ok(taskService.getWorkload());
    }

    @GetMapping("/{publicId}")
    public ResponseEntity<TaskResponse> getById(@PathVariable UUID publicId) {
        return ResponseEntity.ok(taskService.getById(publicId));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("hasAuthority('TASK_UPDATE')")
    public ResponseEntity<TaskResponse> update(
            @PathVariable UUID publicId, @Valid @RequestBody UpdateTaskRequest request) {
        return ResponseEntity.ok(taskService.update(publicId, request));
    }

    @RequestMapping(value = "/{publicId}/status", method = {RequestMethod.PATCH, RequestMethod.PUT})
    @PreAuthorize("hasAuthority('TASK_UPDATE')")
    public ResponseEntity<TaskResponse> changeStatus(
            @PathVariable UUID publicId, @Valid @RequestBody UpdateTaskStatusRequest request) {
        return ResponseEntity.ok(taskService.changeStatus(publicId, request.getStatus()));
    }

    @RequestMapping(value = "/{publicId}/complete", method = {RequestMethod.PATCH, RequestMethod.PUT})
    @PreAuthorize("hasAuthority('TASK_UPDATE')")
    public ResponseEntity<TaskResponse> complete(@PathVariable UUID publicId) {
        return ResponseEntity.ok(taskService.markComplete(publicId));
    }

    @PostMapping("/{publicId}/logs")
    @PreAuthorize("hasAuthority('TASK_UPDATE')")
    public ResponseEntity<TaskResponse> addLog(
            @PathVariable UUID publicId, @Valid @RequestBody AddTaskLogRequest request) {
        return ResponseEntity.ok(taskService.addLog(publicId, request.getLog()));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAuthority('TASK_DELETE')")
    public ResponseEntity<Void> delete(@PathVariable UUID publicId) {
        taskService.delete(publicId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Strict tab parse — an unknown tab is a 404, not a silent fallback to ALL.
     *
     * <p>Silently widening is how {@code ?status=OVERDUE} on the legacy list ends up returning every
     * task: the filter is dropped and the caller cannot tell.
     */
    private static TaskTab parseTab(String raw) {
        if (raw == null || raw.isBlank()) return TaskTab.ALL;
        try {
            return TaskTab.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException("Unknown tab: " + raw);
        }
    }

    /** Lenient ISO-8601 instant parse for optional query params (e.g. {@code 2025-05-01T00:00:00Z}). */
    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}