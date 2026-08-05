package com.crm.travelcrm.task.enums;

/**
 * The left-rail tabs of the All Tasks screen (Today / Yesterday / Overdue / Upcoming / All).
 *
 * <p>Not persisted — this is a query-shape selector only, so it carries no DB CHECK constraint and
 * needs no migration when a tab is added.
 *
 * <h2>Semantics</h2>
 * Every date tab is measured against the <b>calendar anchor</b> {@code COALESCE(start_at, due_date)},
 * the same expression {@link com.crm.travelcrm.task.entity.Task#isOverdue()} and the board/calendar
 * window already use. A task with neither timestamp is board-only and appears in {@link #ALL} alone.
 *
 * <p>Day boundaries are resolved in the <b>tenant's</b> timezone, never UTC — an agency in IST
 * starts its day at 00:00 IST, and the pre-existing {@code ZoneOffset.UTC} arithmetic in
 * {@code TaskServiceImpl.getStats()} put a 6pm task into "tomorrow".
 *
 * <p>{@link #TODAY} and {@link #OVERDUE} deliberately <b>overlap</b>: a task due at 11:00 today,
 * read at 14:00, is both due-today and overdue. That is exactly what the reference product shows
 * (its Today tab renders an "Overdue" badge on such rows), and de-overlapping them would make a
 * task vanish from Today the moment it slipped.
 *
 * <p>{@link #ALL} is the only tab that shows {@code DONE}/{@code CANCELLED} work; the four date tabs
 * are about outstanding work and restrict to the open statuses.
 */
public enum TaskTab {

    /** Anchor falls inside the tenant's today. Open statuses only. */
    TODAY,

    /** Anchor falls inside the tenant's yesterday. Open statuses only. */
    YESTERDAY,

    /** Anchor is already in the past, however far back. Open statuses only. */
    OVERDUE,

    /** Anchor is on or after the tenant's tomorrow. Open statuses only. */
    UPCOMING,

    /** No date restriction and no status restriction — everything not soft-deleted. */
    ALL
}
