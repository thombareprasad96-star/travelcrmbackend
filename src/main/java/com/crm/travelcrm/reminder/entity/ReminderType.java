package com.crm.travelcrm.reminder.entity;

/**
 * Reminder category. Constant names match the frontend dropdown values exactly
 * (case-sensitive) so Jackson can bind them by name with {@code @Enumerated(EnumType.STRING)}.
 *
 * <p>Source of truth: {@code reminders/CreateReminder.jsx} → {@code REMINDER_TYPES}.
 */
public enum ReminderType {
    First_contact,
    Follow_up,
    Quotation,
    Payment,
    Document,
    Birthday,
    Confirmation,
    Custom
}