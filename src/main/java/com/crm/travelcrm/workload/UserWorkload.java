package com.crm.travelcrm.workload;

/**
 * One user's open workload, broken down by what it is made of.
 *
 * <p>The components are kept separate rather than pre-summed because the workload dashboard shows
 * the breakdown while the assignment engine only needs {@link #score()} — and a UI that displays
 * different numbers from the ones the assignment balanced on is how "why did that lead go to the
 * busiest person?" tickets start.
 *
 * @param userId       internal id — this type never crosses an API boundary
 * @param todo         TODO tasks
 * @param inProgress   IN_PROGRESS tasks
 * @param activeLeads  leads in a non-terminal stage ({@code LeadStageGroups.ACTIVE_STAGES})
 * @param openReminders reminders that are {@code Active} or {@code OVERDUE}
 */
public record UserWorkload(
        Long userId,
        long todo,
        long inProgress,
        long activeLeads,
        long openReminders
) {

    /** A user with nothing on their plate — the most attractive assignment candidate. */
    public static UserWorkload empty(Long userId) {
        return new UserWorkload(userId, 0, 0, 0, 0);
    }

    /**
     * <b>The workload metric.</b> {@code todo + inProgress + activeLeads + openReminders}.
     *
     * <p>Deliberately excludes DONE and CANCELLED tasks, and Completed/Dismissed/Snoozed reminders:
     * this measures what a person still has to do, not what they have ever done. The calendar's
     * workload tab used to sort on a task {@code total} that counted DONE, which meant finishing work
     * made you look busier and the best-performing agent got starved of new leads. That is the exact
     * bug this single definition exists to prevent — both the sort and the auto-assignment read it.
     *
     * <p>Snoozed reminders are excluded on purpose: a snooze is an explicit "not now", so counting it
     * would penalise the person who triaged their queue.
     */
    public long score() {
        return todo + inProgress + activeLeads + openReminders;
    }
}
