package com.crm.travelcrm.reminder.entity;

/**
 * Reminder priority. Constant names match the frontend status badges exactly.
 *
 * <p>Source of truth: {@code reminders/Reminders.jsx} → {@code PRIORITIES}.
 */
public enum ReminderPriority {
    High,
    Medium,
    Low
}