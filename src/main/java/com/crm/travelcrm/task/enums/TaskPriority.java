package com.crm.travelcrm.task.enums;

/**
 * Task priority. Persisted as the enum NAME ({@code @Enumerated(EnumType.STRING)}); the DB CHECK
 * constraint {@code tasks_priority_check} in {@code db/indexes.sql} must list every value here.
 *
 * <p>Declaration order is deliberately low→high so {@link #weight()} can rank tasks for the
 * "My Tasks" sort (ordering by the persisted STRING value would sort alphabetically, which is
 * meaningless — always rank in memory via {@link #weight()}).
 */
public enum TaskPriority {
    LOW,
    MEDIUM,
    HIGH,
    URGENT;

    /** Higher = more urgent. Used to sort My-Tasks; DB string ordering is not meaningful. */
    public int weight() {
        return ordinal();
    }
}