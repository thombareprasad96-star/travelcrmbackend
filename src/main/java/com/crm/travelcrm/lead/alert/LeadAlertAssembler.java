package com.crm.travelcrm.lead.alert;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.lead.alert.dto.LeadAlertDto;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.entity.LeadItinerary;
import com.crm.travelcrm.lead.service.LeadAccessGuard;
import com.crm.travelcrm.lead.sla.LeadSlaPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;

/**
 * Turns a {@link Lead} into the broadcast payload.
 *
 * <p>One assembler, used by both the live SSE push and the REST feed, so a lead looks identical
 * whether it arrived as a toast or was on screen when the page loaded. Two builders would drift —
 * and the drift would show up as a row that changes shape when the user refreshes.
 *
 * <p><b>Must be called inside the transaction that loaded the lead.</b> It touches
 * {@code assignedUser} and {@code itinerary}, both lazy; the whole point of assembling here rather
 * than in the after-commit callback is that the session is still open at this moment.
 */
@Component
@RequiredArgsConstructor
public class LeadAlertAssembler {

    private final LeadSlaPolicy slaPolicy;

    /** Snapshot a lead for a NEW_LEAD broadcast or a feed row (no ownership-change context). */
    public LeadAlertDto toAlert(Lead lead) {
        return toAlert(lead, null, null);
    }

    /**
     * Snapshot a lead for a CLAIMED / LOCKED broadcast.
     *
     * @param previousOwnerName who held it before, for "taken from Priya" wording
     * @param actorName         who performed the act
     */
    public LeadAlertDto toAlert(Lead lead, String previousOwnerName, String actorName) {
        LocalDateTime now = LocalDateTime.now();
        User owner = lead.getAssignedUser();

        return LeadAlertDto.builder()
                .leadPublicId(lead.getPublicId())
                .leadCode(lead.getLeadCode())
                .customerName(lead.getCustomerName())
                .phone(lead.getPhone())
                .source(lead.getLeadSource() == null ? null : lead.getLeadSource().getDisplayName())
                // The enum NAME, not the display name: the FE's source-colour map keys off this, and
                // display names are prose that can be reworded without anyone thinking about CSS.
                .sourceKey(lead.getLeadSource() == null ? null : lead.getLeadSource().name())
                .destination(resolveDestination(lead))
                .adults(lead.getAdults())
                .children(lead.getChildren())
                .infants(lead.getInfants())
                .value(lead.getBudget())
                .ownerPublicId(owner == null ? null : owner.getPublicId())
                .ownerName(owner == null ? null : owner.getName())
                .claimVersion(lead.getClaimVersion() == null ? 0 : lead.getClaimVersion())
                .openToClaim(LeadAccessGuard.isOpenToClaim(lead))
                .leadStage(lead.getLeadStage() == null ? null : lead.getLeadStage().getDisplayName())
                .createdAt(lead.getCreatedAt())
                .slaTargetSeconds(slaPolicy.effectiveTargetSeconds(lead))
                .slaSecondsRemaining(slaPolicy.secondsRemaining(lead, now))
                .previousOwnerName(previousOwnerName)
                .actorName(actorName)
                .build();
    }

    /**
     * Where the customer wants to go, in one line.
     *
     * <p>First itinerary stop by day number, falling back to the lead's departure city. The
     * fallback is not cosmetic: an inbound enquiry from a webhook usually has no itinerary at all,
     * and a blank destination is the single field that makes an alert row unreadable at a glance —
     * "Rohit Verma · 2 Adults · ₹85,000" tells an agent nothing about whether to take the call.
     */
    private String resolveDestination(Lead lead) {
        if (lead.getItinerary() != null && !lead.getItinerary().isEmpty()) {
            String fromItinerary = lead.getItinerary().stream()
                    // Null day numbers sort last rather than throwing — legacy rows predate the field.
                    .min(Comparator.comparing(LeadItinerary::getDayNumber,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(i -> i.getCity() != null && !i.getCity().isBlank()
                            ? i.getCity()
                            : i.getDestination())
                    .orElse(null);
            if (fromItinerary != null && !fromItinerary.isBlank()) {
                return fromItinerary;
            }
        }
        String departCity = lead.getDepartCity();
        // "Not Specified" is the literal the create form posts for an untouched origin field;
        // echoing it into an alert would read as a real place name.
        if (departCity == null || departCity.isBlank() || "Not Specified".equalsIgnoreCase(departCity.trim())) {
            return null;
        }
        return departCity;
    }
}
