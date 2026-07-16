package com.crm.travelcrm.task.enums;

/**
 * Task/event category — drives the calendar legend colour and grouping.
 *
 * <p>Persisted as the enum NAME ({@code @Enumerated(EnumType.STRING)}); the DB CHECK constraint
 * {@code tasks_category_check} in {@code db/indexes.sql} must list every value here. {@code MEETING}
 * backs the calendar's "Team Meetings" category; the rest let staff tag ad-hoc to-dos so they
 * colour-match the derived booking/reminder events on the same calendar.
 */
public enum TaskCategory {
    GENERAL,
    FOLLOW_UP,
    CALL,
    MEETING,
    PAYMENT,
    DOCUMENT,
    VISA,
    TRAVEL,
    OTHER;

    /** Human label shown on the calendar legend / event chip. */
    public String label() {
        return switch (this) {
            case GENERAL   -> "General";
            case FOLLOW_UP -> "Follow-up";
            case CALL      -> "Call";
            case MEETING   -> "Team Meeting";
            case PAYMENT   -> "Payment";
            case DOCUMENT  -> "Document";
            case VISA      -> "Visa";
            case TRAVEL    -> "Travel";
            case OTHER     -> "Other";
        };
    }
}