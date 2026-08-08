package com.crm.travelcrm.lead.sla;

import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.enums.LeadOriginGroups;
import com.crm.travelcrm.lead.enums.LeadStageGroups;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * The first-response SLA: how long a brand-new lead may sit before someone contacts the customer.
 *
 * <p><b>The target is snapshotted onto the lead at creation</b> ({@code Lead.slaTargetSeconds}) and
 * every later question is answered against that pinned value, never against the live setting. This
 * is the same anti-retroactivity rule a booking's cancellation policy follows, and for the same
 * reason: raising the target from 5 to 10 minutes next month must not silently un-breach last
 * month's leads. An SLA report that changes its own history is worse than no report.
 *
 * <p><b>Per-tenant override.</b> Today the target is one configured value for the deployment. The
 * seam for per-tenant targets is {@link #targetSecondsForNewLead(Long)} — it already takes the
 * tenant and is the ONLY place a new lead's target is decided, so adding a per-tenant column later
 * changes this method and nothing else. Leads already created keep the target they were stamped
 * with, which is exactly the desired behaviour and comes for free from pinning.
 */
@Component
@Slf4j
public class LeadSlaPolicy {

    /**
     * Deployment default, in seconds. 300 (5 minutes) matches the product brief. Bounded on read so
     * a fat-fingered {@code 0} or a negative value cannot make every lead breach instantly.
     */
    private final int defaultTargetSeconds;

    /** Floor and ceiling for any configured target: 30 seconds to 24 hours. */
    static final int MIN_TARGET_SECONDS = 30;
    static final int MAX_TARGET_SECONDS = 86_400;

    public LeadSlaPolicy(
            @Value("${app.lead.sla.first-response-seconds:300}") int configuredTargetSeconds) {
        this.defaultTargetSeconds = clamp(configuredTargetSeconds);
        if (this.defaultTargetSeconds != configuredTargetSeconds) {
            log.warn("app.lead.sla.first-response-seconds={} is outside [{}, {}] — clamped to {}s.",
                    configuredTargetSeconds, MIN_TARGET_SECONDS, MAX_TARGET_SECONDS,
                    this.defaultTargetSeconds);
        }
    }

    /**
     * The target to stamp on a lead being created now. Takes the tenant so the per-tenant override
     * can land here without touching a single caller.
     */
    public int targetSecondsForNewLead(Long tenantId) {
        return defaultTargetSeconds;
    }

    /**
     * The target that applies to an EXISTING lead: its pinned value, falling back to the current
     * default only for rows written before the column existed (where nothing was ever pinned).
     */
    public int effectiveTargetSeconds(Lead lead) {
        Integer pinned = lead.getSlaTargetSeconds();
        return pinned == null ? defaultTargetSeconds : clamp(pinned);
    }

    /**
     * Whether this lead missed its first-response target, evaluated at {@code now}.
     *
     * <p>Two distinct cases, both breaches:
     * <ul>
     *   <li><b>Answered late</b> — contacted, but {@code firstResponseSeconds} exceeded the target.</li>
     *   <li><b>Still unanswered</b> — never contacted and already past the target. This one is the
     *       reason breaches are computed live rather than stored: a lead nobody touches becomes a
     *       breach through the passage of time, with no write to hang a stored flag on. A scheduler
     *       could stamp it, but then the tile's number depends on whether that job last ran.</li>
     * </ul>
     *
     * <p>Terminal leads that were never contacted are NOT breaches — a junk enquiry binned as LOST
     * without a call is correct handling, not a missed SLA.
     */
    public boolean isBreached(Lead lead, LocalDateTime now) {
        if (!LeadOriginGroups.isInbound(lead.getOrigin())) {
            return false;
        }
        int target = effectiveTargetSeconds(lead);

        Long answeredIn = lead.getFirstResponseSeconds();
        if (answeredIn != null) {
            return answeredIn > target;
        }
        if (lead.getFirstContactedAt() != null) {
            // Contacted but unmeasured (a row predating the column) — nothing to judge.
            return false;
        }
        if (!LeadStageGroups.isActive(lead.getLeadStage())) {
            return false;
        }
        LocalDateTime createdAt = lead.getCreatedAt();
        if (createdAt == null) {
            return false;
        }
        return Duration.between(createdAt, now).getSeconds() > target;
    }

    /**
     * Seconds remaining before this lead breaches; negative once it has. {@code null} when the clock
     * no longer runs (already contacted, or terminal) — the UI renders "stopped" rather than a
     * countdown, and a caller that treats null as zero would show every closed lead as breaching.
     */
    public Long secondsRemaining(Lead lead, LocalDateTime now) {
        if (!LeadOriginGroups.isInbound(lead.getOrigin())
                || lead.getFirstContactedAt() != null
                || !LeadStageGroups.isActive(lead.getLeadStage())) {
            return null;
        }
        LocalDateTime createdAt = lead.getCreatedAt();
        if (createdAt == null) {
            return null;
        }
        return effectiveTargetSeconds(lead) - Duration.between(createdAt, now).getSeconds();
    }

    private static int clamp(int seconds) {
        return Math.max(MIN_TARGET_SECONDS, Math.min(MAX_TARGET_SECONDS, seconds));
    }
}
