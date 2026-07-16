package com.crm.travelcrm.task.controller;

import com.crm.travelcrm.task.dto.AddTaskLogRequest;
import com.crm.travelcrm.task.dto.CreateTaskRequest;
import com.crm.travelcrm.task.dto.TaskResponse;
import com.crm.travelcrm.task.dto.TaskStatsDto;
import com.crm.travelcrm.task.dto.UpdateTaskRequest;
import com.crm.travelcrm.task.dto.UpdateTaskStatusRequest;
import com.crm.travelcrm.task.dto.TaskWorkloadDto;
import com.crm.travelcrm.task.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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