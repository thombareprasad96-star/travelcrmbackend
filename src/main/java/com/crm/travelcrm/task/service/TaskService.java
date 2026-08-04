package com.crm.travelcrm.task.service;

import com.crm.travelcrm.task.dto.CreateTaskRequest;
import com.crm.travelcrm.task.dto.TaskResponse;
import com.crm.travelcrm.task.dto.TaskStatsDto;
import com.crm.travelcrm.task.dto.TaskTabCountsDto;
import com.crm.travelcrm.task.dto.TaskWorkloadDto;
import com.crm.travelcrm.task.dto.UpdateTaskRequest;
import com.crm.travelcrm.task.enums.TaskStatus;
import com.crm.travelcrm.task.enums.TaskTab;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Task board + team-workload operations. All queries are tenant-scoped and sub-agent row-scoped. */
public interface TaskService {

    TaskResponse create(CreateTaskRequest request);

    TaskResponse update(UUID publicId, UpdateTaskRequest request);

    void delete(UUID publicId);

    TaskResponse getById(UUID publicId);

    /** Board / calendar list with optional filters. {@code from}/{@code to} bound the calendar anchor. */
    List<TaskResponse> list(String status, String priority, String category,
                            UUID assigneePublicId, Instant from, Instant to);

    /**
     * The All Tasks grid: one tab, paged and row-scoped.
     *
     * <p>Separate from {@link #list} on purpose — {@code GET /api/tasks} returns a bare unpaginated
     * array that the existing calendar/board frontend reads as {@code res.data}, and adding
     * pagination to it would break that contract.
     */
    Page<TaskResponse> listPaged(TaskTab tab, String search, UUID assigneePublicId,
                                 String status, String priority, String category,
                                 int page, int size, String sortBy, String sortDir);

    /**
     * Badge counts for all five tabs, computed from the same scoped specification {@link #listPaged}
     * uses so a badge can never disagree with the list it opens. Honours every filter except the tab.
     */
    TaskTabCountsDto tabCounts(String search, UUID assigneePublicId,
                               String status, String priority, String category);

    /** Open tasks assigned to the current user (My Tasks), ranked by priority then due date. */
    List<TaskResponse> getMyTasks();

    TaskResponse changeStatus(UUID publicId, TaskStatus status);

    TaskResponse markComplete(UUID publicId);

    TaskResponse addLog(UUID publicId, String logText);

    /** Tenant-wide counters (CRM_FULL-gated at the controller — blocks sub-agents). */
    TaskStatsDto getStats();

    /** Tenant-wide per-assignee workload (CRM_FULL-gated at the controller — blocks sub-agents). */
    List<TaskWorkloadDto> getWorkload();
}