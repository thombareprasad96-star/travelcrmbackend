package com.crm.travelcrm.reminder.entity;

/**
 * Reminder lifecycle status. Constant names match the frontend status badges
 * exactly (case-sensitive).
 *
 * <p>Source of truth: {@code reminders/Reminders.jsx} → {@code STATUSES}.
 *
 * <p>{@code OVERDUE} is set by {@code ReminderScheduler} once an {@code Active} reminder's
 * {@code dueDate} has passed (per the agreed spec). The frontend additionally derives
 * "overdue" client-side from {@code dueDate}.
 */
public enum ReminderStatus {
    Active,
    Snoozed,
    Completed,
    Dismissed,
    OVERDUE
}