package com.crm.travelcrm.lead.dto;

import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.enums.LeadType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the All-Leads dashboard cards need, computed in the DATABASE over the caller's full
 * row-level scope — not over whatever page the table happens to be holding.
 *
 * <p><b>Why this exists.</b> The cards used to be a {@code useMemo} over the leads array the list
 * had already fetched, and that fetch is {@code page=0&size=100}. Past 100 leads every number —
 * totals, conversion, tab badges — silently described the newest 100 rows while reading like a
 * tenant-wide figure. Worse, "conversion %" over the NEWEST hundred is structurally pessimistic:
 * the leads most likely to be in it are the ones that have had the least time to convert.
 *
 * <p>Every count here is scoped exactly like {@code getAllLeads}: tenant + soft-delete +
 * {@code ScopeResolver.visibleUserIds(user, "LEAD_READ")}. So one endpoint serves all three roles —
 * a TENANT_ADMIN sees the tenant, a manager sees their team, an agent sees their own — with no
 * role-specific branches in the client.
 *
 * <p><b>Not included on purpose:</b> the unclaimed/SLA figures. Those live on
 * {@code GET /api/leads/alerts/stats} and are deliberately tenant-WIDE (an open lead is visible to
 * everyone so anyone can claim it). Mixing the two vocabularies into one payload would invite the
 * client to add a scoped number to a tenant-wide one.
 */
@Data
@Builder
public class LeadStatsSummaryDto {

    // ── Pipeline shape (all-time, current state) ─────────────────────────────

    /** Every non-deleted lead in scope, terminal ones included. */
    private long totalLeads;

    /** Anything not CONVERTED/LOST — {@code LeadStageGroups.ACTIVE_STAGES}, REOPENED included. */
    private long activeLeads;

    /**
     * Leads whose stage is CONVERTED right now. Deliberately a CURRENT-STATE count, not a
     * historical one: cancelling a booking with MOVE_TO_LEAD flips the lead back to REOPENED and
     * clears {@code convertedAt}, so this number can go DOWN. For "how many did we win in March",
     * read {@link #convertedInPeriod}, which is measured on {@code convertedAt}.
     */
    private long convertedLeads;

    private long lostLeads;

    /** Quoted and waiting on the customer — the highest-intent bucket on the board. */
    private long proposalSentLeads;

    /** Every stage in enum order, zero-filled, so the client never has to guess a missing key. */
    private List<StageCount> byStage;

    /** Every type in enum order, zero-filled. Drives the "Fresh" tab badge and the Hot card. */
    private List<TypeCount> byType;

    // ── Money in play ────────────────────────────────────────────────────────

    /**
     * Σ {@code budget} over ACTIVE leads. Budget is what the customer said they'd spend — it is not
     * revenue and it is not profit, which is why it needs no extra permission gate.
     */
    private BigDecimal activePipelineValue;

    /**
     * How many active leads actually carry a budget. Ship this next to the sum or the sum reads as
     * "the whole pipeline is worth this", when it may be four leads out of eighty.
     */
    private long activeWithBudget;

    /**
     * Σ grand total of the LATEST quotation of every PROPOSAL_SENT lead — real quoted money, as
     * opposed to the customer's stated budget.
     *
     * <p>Computed through {@code QuotationService.getLatestRefsByLeads}, i.e. the same shared
     * pricing formula the lead list itself renders. It is NOT a SQL {@code SUM}: {@code Quotation}
     * stores no grand-total column, it is derived from the per-service amounts plus discount/tax/
     * markup, so a hand-written SQL sum would be a second copy of that formula, free to drift.
     */
    private BigDecimal quotedValue;

    /**
     * False when there were more PROPOSAL_SENT leads than the aggregation cap and the figure above
     * is therefore a floor. The client must render it as an approximation — a silently truncated
     * money figure is worse than no money figure.
     */
    private boolean quotedValueComplete;

    // ── Today's work ─────────────────────────────────────────────────────────

    /** Active leads whose {@code followUpDate} is already past — measured in the tenant's zone. */
    private long followUpsOverdue;

    /** Active leads due for follow-up today. */
    private long followUpsDueToday;

    /** The tenant-local date all "today" figures were computed against. */
    private LocalDate today;

    // ── Period window ────────────────────────────────────────────────────────

    /** Echoed back so the card can caption itself with the window it is actually showing. */
    private LocalDate periodFrom;
    /** Inclusive. */
    private LocalDate periodTo;

    private long createdInPeriod;

    /** Leads whose {@code convertedAt} falls in the window — "how many we won in this period". */
    private long convertedInPeriod;

    /**
     * Of the leads CREATED in this window, how many are converted now. The cohort numerator that
     * belongs with {@link #createdInPeriod}; pairing {@link #convertedInPeriod} with it instead
     * would divide two different populations.
     */
    private long cohortConverted;

    /**
     * {@code cohortConverted / createdInPeriod} as a percentage, or null when no leads were created
     * in the window. Null, not 0 — "nothing to convert yet" and "converted nothing" are opposite
     * facts and must not render the same.
     */
    private Double conversionRate;

    @Data
    @AllArgsConstructor
    public static class StageCount {
        /** Serialises as the display name ("Proposal Sent") — the wire format the FE compares. */
        private LeadStage stage;
        private long count;
    }

    @Data
    @AllArgsConstructor
    public static class TypeCount {
        private LeadType type;
        private long count;
    }
}
