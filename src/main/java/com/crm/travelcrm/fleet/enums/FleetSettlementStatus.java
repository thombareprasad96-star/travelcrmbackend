package com.crm.travelcrm.fleet.enums;

/**
 * The lifecycle of one trip's money. <b>This is the only approval workflow in the fleet module</b> —
 * there is deliberately none on the individual expense row.
 *
 * <pre>
 *   OPEN ──reconcile──&gt; RECONCILED ──settle(signed)──&gt; SETTLED ──period close──&gt; LOCKED
 *    ▲                       │
 *    └────── reopen ─────────┘
 * </pre>
 *
 * <p><b>Why not DRAFT/SUBMITTED/APPROVED/REJECTED/VOID per expense.</b> All four domain
 * practitioners rejected it independently, in the same words: four approvals for a Rs 640 parking
 * charge is ceremony nobody performs, so it gets worked around — costs go unrecorded, or everything
 * is approved in a batch without being read, which is worse than no approval at all. Approval is a
 * real decision exactly once: when the manager looks at the whole trip beside the driver's cash and
 * releases him.
 *
 * <p><b>Corrections.</b> While {@link #OPEN}, rows are freely edited — a typo at the pump is a typo,
 * not a financial event. From {@link #SETTLED} onward the numbers are signed and immutable, and a
 * correction is a dated reversal row that stands beside the original. That is what makes "void"
 * unambiguous: it is never a status flip on an approved row, so a closed period can never change
 * retroactively.
 *
 * <p><b>{@link #LOCKED} is owner-controlled, by financial year and month</b> — not a rolling
 * {@code expense-lock-days} timer. Challans and pump bills routinely arrive 30-60 days late; a
 * 7-day cut-off simply means they never get recorded.
 */
public enum FleetSettlementStatus {

    /** Trip is running or just back. Costs and cash entries are added and edited freely. */
    OPEN("Open"),

    /** Numbers agree — advance, spend, collections and returns balance. The sheet is printable. */
    RECONCILED("Reconciled"),

    /** Driver has acknowledged and been released. Immutable; corrections are reversal entries. */
    SETTLED("Settled"),

    /** Inside a closed accounting period. Nothing may be added, reversed or dated into it. */
    LOCKED("Locked");

    private final String label;

    FleetSettlementStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Can rows still be added or edited in place? */
    public boolean isMutable() {
        return this == OPEN || this == RECONCILED;
    }

    /** Are new financial rows accepted at all (late challan, late pump bill) — as reversals/additions? */
    public boolean acceptsLateEntries() {
        return this != LOCKED;
    }
}
