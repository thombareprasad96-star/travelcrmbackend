package com.crm.travelcrm.lead.alert;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.context.TenantTimeZone;
import com.crm.travelcrm.lead.alert.dto.LeadAlertDto;
import com.crm.travelcrm.lead.alert.dto.LeadAlertStatsDto;
import com.crm.travelcrm.lead.enums.LeadStageGroups;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.lead.sla.LeadSlaPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Reads behind the incoming-leads screen: the claimable feed, and the four tiles above it.
 *
 * <p>Both are tenant-wide and deliberately NOT row-scoped. An open lead is visible to the whole
 * tenant so anyone can claim it (the approved claim-window widening), and a tile that counted only
 * the caller's own leads would tell five agents five different numbers for "Open to Claim" — the
 * one figure they are all supposed to be racing to drive down.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeadAlertService {

    private final LeadRepository leadRepository;
    private final LeadAlertAssembler assembler;
    private final LeadSlaPolicy slaPolicy;
    private final TenantTimeZone tenantTimeZone;

    /**
     * Hard ceiling on the feed. The claim window is minutes long, so a healthy tenant has a handful
     * of open leads; a number this size means something is wrong (nobody working, or an ingest
     * flood) and paging through 500 of them is not the answer. Capped rather than paginated so the
     * screen stays a live list and not a table with a pager that goes stale on every push.
     */
    private static final int MAX_OPEN_FEED = 200;

    /** Every lead currently open to claim, newest first. */
    @Transactional(readOnly = true)
    public List<LeadAlertDto> openToClaim() {
        Long tenantId = requireTenantId();
        List<LeadAlertDto> feed = leadRepository
                .findOpenToClaim(tenantId, LeadStageGroups.TERMINAL_STAGES,
                        PageRequest.of(0, MAX_OPEN_FEED))
                .stream()
                .map(assembler::toAlert)
                .toList();

        if (feed.size() == MAX_OPEN_FEED) {
            // Silent truncation would read as "this is all of them" on the one screen whose entire
            // job is that nothing is missed.
            log.warn("Open-to-claim feed hit its {}-row cap for tenant {} — leads are not being "
                    + "picked up, or ingest is flooding.", MAX_OPEN_FEED, tenantId);
        }
        return feed;
    }

    /** The four tiles, all for TODAY in the tenant's own timezone. */
    @Transactional(readOnly = true)
    public LeadAlertStatsDto stats() {
        Long tenantId = requireTenantId();

        // The tenant's local day, not the server's. An agency in Kathmandu must not have its last
        // hour of leads counted against tomorrow because the JVM happens to run in UTC.
        ZoneId zone = tenantTimeZone.forTenant(tenantId);
        LocalDate today = LocalDate.now(zone);
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime dayEnd = today.plusDays(1).atStartOfDay();
        LocalDateTime now = LocalDateTime.now(zone);

        int target = slaPolicy.targetSecondsForNewLead(tenantId);

        Double avg = leadRepository.avgFirstResponseSeconds(tenantId, dayStart, dayEnd);

        return LeadAlertStatsDto.builder()
                .newToday(leadRepository.countCreatedBetween(tenantId, dayStart, dayEnd))
                .openToClaim(leadRepository.countOpenToClaim(
                        tenantId, LeadStageGroups.TERMINAL_STAGES))
                // Null stays null all the way to the client: "nobody contacted yet" and "answered
                // instantly" are opposite facts and must not both render as 0.
                .avgFirstResponseSeconds(avg == null ? null : Math.round(avg))
                .slaTargetSeconds(target)
                .slaBreaches(leadRepository.countSlaBreaches(
                        tenantId, dayStart, dayEnd, now, target, LeadStageGroups.TERMINAL_STAGES))
                .build();
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty for the lead alert feed.");
        }
        return tenantId;
    }
}
