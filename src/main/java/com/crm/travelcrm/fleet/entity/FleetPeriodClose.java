package com.crm.travelcrm.fleet.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * A closed accounting month. The enforcement point for immutability — and the thing the design was
 * missing entirely until an adversarial review pointed at the hole.
 *
 * <p><b>Why a settlement status is not enough.</b> {@code FleetSettlementStatus.LOCKED} is a property
 * of one trip's row. On 15 April, after the March year-end close, a user creates a NEW trip dated
 * 28 March: its settlement is brand new and OPEN, so Rs 40,000 of March-dated expenses post freely
 * and a closed, reported, possibly-filed figure moves. Worse, an expense with no trip at all —
 * insurance, road tax, an off-duty challan, typically the largest non-fuel rows in the book — has no
 * settlement row to consult in the first place, so the module's strongest control covered its
 * smallest money and its weakest covered its largest.
 *
 * <p>So the lock is keyed on the PERIOD, not on a trip. Every write to {@code fleet_expenses} and
 * {@code fleet_cash_entries} checks its {@code postingDate} against this table, independent of any
 * trip. {@code FLEET_PERIOD_CLOSE} is the permission that authorises writing here.
 *
 * <p><b>Closing is on posting date, attribution is on document date.</b> That is what makes a
 * post-close correction legal rather than impossible: a March receipt found in June is reversed
 * carrying its March document date — so March's vehicle cost/km is corrected — with a June posting
 * date, so the closed period's total does not move. This is exactly what real books do, and without
 * the two-date split the choice is between leaving a known-false figure on the books forever and
 * reopening a closed year.
 *
 * <p>Closed by financial year and month, by a human. Deliberately NOT a rolling {@code
 * expense-lock-days} timer — challans and pump bills routinely arrive 30-60 days late, and a 7-day
 * cut-off means they are never recorded at all.
 */
@Entity
@Table(name = "fleet_period_closes", indexes = {
        @Index(name = "idx_fleet_period_tenant", columnList = "tenant_id,financial_year,period_month")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class FleetPeriodClose extends BaseTenantEntity {

    /** Indian FY start year — 2026 means FY 2026-27, i.e. 1 Apr 2026 to 31 Mar 2027. */
    @Column(name = "financial_year", nullable = false)
    private Integer financialYear;

    /** Calendar month 1-12. Unique with (tenant, financialYear) — index in SQL. */
    @Column(name = "period_month", nullable = false)
    private Integer periodMonth;

    @Column(name = "closed_at", nullable = false)
    private LocalDateTime closedAt;

    @Column(name = "closed_by", length = 150)
    private String closedBy;

    /**
     * A close can be lifted — by someone holding {@code FLEET_PERIOD_CLOSE}, with a reason, and it is
     * recorded rather than deleted. Reopening a filed period is a serious act and the audit trail
     * should show that it happened, not just that the row is gone.
     */
    @Column(name = "reopened_at")
    private LocalDateTime reopenedAt;

    @Column(name = "reopened_by", length = 150)
    private String reopenedBy;

    @Column(name = "reopen_reason", length = 300)
    private String reopenReason;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** Closed and not since reopened. */
    public boolean isActive() {
        return reopenedAt == null;
    }
}
