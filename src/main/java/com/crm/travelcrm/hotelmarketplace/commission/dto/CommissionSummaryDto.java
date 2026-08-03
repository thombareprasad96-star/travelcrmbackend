package com.crm.travelcrm.hotelmarketplace.commission.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The platform earning report over a filtered slice of the ledger (design doc §13).
 *
 * <p>Two different cuts of the same rows, and they answer different questions:</p>
 * <ul>
 *   <li><b>By entry type</b> — {@link #grossAccrued}, {@link #totalReversed}, {@link #totalAdjusted}:
 *       what happened. Reads as the story of the period.</li>
 *   <li><b>By status</b> — {@link #pendingBalance} … {@link #settledBalance}: how much of the net is
 *       real yet, and how much is still waiting on a stay to happen.</li>
 * </ul>
 *
 * <p>{@link #netEarning} is the authoritative figure and is a straight signed sum of every live row;
 * the two breakdowns each sum to it. A partial reversal deliberately leaves the untouched remainder of
 * the original accrual in {@link #pendingBalance}, so the status buckets describe where the money sits,
 * not how much of it was originally booked.</p>
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommissionSummaryDto {

    // ── By entry type ───────────────────────────────────────────────────────
    /** Σ ACCRUAL. Positive. What the platform booked before anything was taken back. */
    BigDecimal grossAccrued;

    /** Σ REVERSAL. Negative, and reported as stored — flipping the sign for display is the UI's job. */
    BigDecimal totalReversed;

    /** Σ ADJUSTMENT. Signed; manual corrections go both ways. */
    BigDecimal totalAdjusted;

    /** Σ SETTLEMENT. Zero in this release — settlement is a status transition, not a row (yet). */
    BigDecimal totalSettlementEntries;

    /** Σ every live row. The number platform revenue reporting is built on. */
    BigDecimal netEarning;

    // ── By status ───────────────────────────────────────────────────────────
    BigDecimal pendingBalance;
    BigDecimal earnedBalance;
    BigDecimal reversedBalance;
    BigDecimal settledBalance;

    long entryCount;
    String currency;

    // ── The filter this was computed over ───────────────────────────────────
    // Echoed back so a report a SuperAdmin exports or screenshots still says what it covers.
    Long tenantId;
    LocalDate fromDate;
    LocalDate toDate;
}
