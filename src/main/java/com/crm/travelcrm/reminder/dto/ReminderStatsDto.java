package com.crm.travelcrm.reminder.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * GET /api/reminders/stats response. Keys match the frontend stats bar exactly:
 * {@code { total, active, overdue, completed, snoozed }}.
 */
@Getter
@Builder
public class ReminderStatsDto {
    private long total;
    private long active;
    private long overdue;
    private long completed;
    private long snoozed;
}