package com.crm.travelcrm.lead.alert.dto;

import lombok.Builder;
import lombok.Data;

/**
 * The four tiles above the incoming-leads list.
 *
 * <p>All four are computed for TODAY in the tenant's own timezone, not the server's. An agency in
 * Kathmandu closing its day at 23:59 local must not have the last hour of leads counted against
 * tomorrow because the JVM runs in UTC.
 */
@Data
@Builder
public class LeadAlertStatsDto {

    /** Leads created today. */
    private long newToday;

    /**
     * Leads currently claimable — NOT restricted to today. A lead that arrived last night and is
     * still unclaimed this morning is exactly what this tile exists to make impossible to miss;
     * scoping it to today would hide the oldest, most urgent ones.
     */
    private long openToClaim;

    /**
     * Mean create → first-contact time in seconds, over leads FIRST CONTACTED today. Null when
     * nobody has been contacted yet — rendered as "—", never as a flattering 0.
     *
     * <p>Grouped by contact day rather than creation day on purpose: this measures how the team is
     * responding today, and a lead created yesterday and answered this morning is part of that.
     */
    private Long avgFirstResponseSeconds;

    /** The target the tile compares against, so the UI never hardcodes 5 minutes. */
    private int slaTargetSeconds;

    /**
     * Breaches among today's leads: answered later than the target, PLUS still-unanswered ones
     * already past it. The second half is why this is a live query and not a stored flag — a lead
     * nobody touches becomes a breach through elapsed time, with no write to hang a flag on.
     */
    private long slaBreaches;
}
