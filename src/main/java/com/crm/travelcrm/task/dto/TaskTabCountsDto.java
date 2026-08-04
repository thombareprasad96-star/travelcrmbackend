package com.crm.travelcrm.task.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Badge counts for the All Tasks left rail, one per {@link com.crm.travelcrm.task.enums.TaskTab}.
 *
 * <p>Computed from the <b>same</b> scoped specification the list endpoint uses, so a badge can never
 * disagree with the list it opens — the failure mode that makes count tiles untrustworthy. This is
 * why the existing {@code GET /api/tasks/stats} cannot back this rail: it is {@code CRM_FULL}-gated
 * and counts tenant-wide with no row scope at all.
 *
 * <p>{@code today} and {@code overdue} overlap by design; see {@code TaskTab}.
 */
@Getter
@Builder
public class TaskTabCountsDto {

    private long today;
    private long yesterday;
    private long overdue;
    private long upcoming;

    /** Every task the caller can see, any status, no date restriction. */
    private long all;
}
