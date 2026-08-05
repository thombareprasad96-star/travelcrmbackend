package com.crm.travelcrm.lead.sla;

import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.enums.LeadStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SLA rules that decide what the "SLA Breaches" tile counts.
 *
 * <p>The interesting cases here are the ones that must NOT count: a junk enquiry binned without a
 * call, and a lead created before the feature existed. Both would otherwise inflate the tile with
 * failures nobody committed, and a metric that cries wolf gets ignored.
 */
class LeadSlaPolicyTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 8, 3, 10, 0, 0);

    private final LeadSlaPolicy policy = new LeadSlaPolicy(300);

    // ── Configuration ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a mistyped zero target is clamped, not obeyed — otherwise every lead breaches on arrival")
    void zeroTargetIsClamped() {
        LeadSlaPolicy broken = new LeadSlaPolicy(0);
        assertThat(broken.targetSecondsForNewLead(1L)).isEqualTo(LeadSlaPolicy.MIN_TARGET_SECONDS);
    }

    @Test
    @DisplayName("an absurdly large target is clamped to 24h")
    void hugeTargetIsClamped() {
        LeadSlaPolicy broken = new LeadSlaPolicy(999_999_999);
        assertThat(broken.targetSecondsForNewLead(1L)).isEqualTo(LeadSlaPolicy.MAX_TARGET_SECONDS);
    }

    // ── Pinning (anti-retroactivity) ─────────────────────────────────────────

    @Test
    @DisplayName("the PINNED target wins over the configured one — raising the target cannot un-breach history")
    void pinnedTargetBeatsConfiguration() {
        Lead lead = openLead();
        lead.setSlaTargetSeconds(60);                       // created under a strict 1-minute target
        lead.setFirstResponseSeconds(120L);                 // answered in 2 minutes

        // The deployment default is now 300s, under which 120s would be fine. The lead was created
        // under 60s, so it breached, and it must stay breached.
        assertThat(policy.effectiveTargetSeconds(lead)).isEqualTo(60);
        assertThat(policy.isBreached(lead, CREATED.plusMinutes(5))).isTrue();
    }

    @Test
    @DisplayName("a row written before the column existed falls back to the configured default")
    void unpinnedLeadFallsBackToDefault() {
        Lead lead = openLead();
        lead.setSlaTargetSeconds(null);
        assertThat(policy.effectiveTargetSeconds(lead)).isEqualTo(300);
    }

    // ── Breach classification ────────────────────────────────────────────────

    @Test
    @DisplayName("answered inside the target is not a breach")
    void answeredInTimeIsNotABreach() {
        Lead lead = openLead();
        lead.setFirstContactedAt(CREATED.plusMinutes(2));
        lead.setFirstResponseSeconds(120L);
        lead.setLeadStage(LeadStage.CONTACTED);

        assertThat(policy.isBreached(lead, CREATED.plusHours(1))).isFalse();
    }

    @Test
    @DisplayName("answered late is a breach forever, however long ago")
    void answeredLateIsABreach() {
        Lead lead = openLead();
        lead.setFirstContactedAt(CREATED.plusMinutes(9));
        lead.setFirstResponseSeconds(540L);
        lead.setLeadStage(LeadStage.CONTACTED);

        assertThat(policy.isBreached(lead, CREATED.plusDays(30))).isTrue();
    }

    @Test
    @DisplayName("never contacted and past the target is a breach — this is why breaches are computed live")
    void unansweredPastTargetIsABreach() {
        Lead lead = openLead();

        assertThat(policy.isBreached(lead, CREATED.plusSeconds(299)))
                .as("still inside the window")
                .isFalse();
        assertThat(policy.isBreached(lead, CREATED.plusSeconds(301)))
                .as("nothing wrote to this row — it became a breach purely through elapsed time")
                .isTrue();
    }

    @Test
    @DisplayName("a junk lead binned as Lost without a call is NOT a breach")
    void terminalUncontactedLeadIsNotABreach() {
        Lead lead = openLead();
        lead.setLeadStage(LeadStage.LOST);

        assertThat(policy.isBreached(lead, CREATED.plusDays(1)))
                .as("correct handling of a junk enquiry, not a missed SLA — counting it would "
                        + "flatter nobody and would punish good triage")
                .isFalse();
    }

    @Test
    @DisplayName("a contacted lead with no measurement (legacy row) is not judged")
    void contactedButUnmeasuredIsNotABreach() {
        Lead lead = openLead();
        lead.setFirstContactedAt(CREATED.plusMinutes(45));
        lead.setFirstResponseSeconds(null);
        lead.setLeadStage(LeadStage.CONTACTED);

        assertThat(policy.isBreached(lead, CREATED.plusDays(1))).isFalse();
    }

    // ── Countdown ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the countdown runs while open and stops once contacted")
    void countdownStopsOnContact() {
        Lead lead = openLead();
        assertThat(policy.secondsRemaining(lead, CREATED.plusSeconds(60))).isEqualTo(240L);

        lead.setFirstContactedAt(CREATED.plusSeconds(60));
        lead.setLeadStage(LeadStage.CONTACTED);
        assertThat(policy.secondsRemaining(lead, CREATED.plusSeconds(90)))
                .as("null means 'clock stopped'; a caller treating it as 0 would show every closed "
                        + "lead as breaching")
                .isNull();
    }

    @Test
    @DisplayName("the countdown goes negative rather than clamping — the UI needs to show how far past")
    void countdownGoesNegative() {
        assertThat(policy.secondsRemaining(openLead(), CREATED.plusSeconds(400))).isEqualTo(-100L);
    }

    private static Lead openLead() {
        Lead lead = new Lead();
        lead.setCreatedAt(CREATED);
        lead.setLeadStage(LeadStage.NEW_LEAD);
        lead.setSlaTargetSeconds(300);
        return lead;
    }
}
