package com.crm.travelcrm.task.enums;

/**
 * Kanban/board lifecycle of a {@link com.crm.travelcrm.task.entity.Task}.
 *
 * <p>Persisted as the enum NAME ({@code @Enumerated(EnumType.STRING)}) — the DB CHECK
 * constraint {@code tasks_status_check} in {@code db/indexes.sql} must list every value here.
 * {@code TODO} and {@code IN_PROGRESS} are the "open" states used by overdue / My-Tasks logic.
 */
public enum TaskStatus {
    TODO,
    IN_PROGRESS,
    DONE,
    CANCELLED
}