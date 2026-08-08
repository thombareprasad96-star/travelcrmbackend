package com.crm.travelcrm.lead.claim.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.lead.assignment.history.LeadAssignmentEvent;
import com.crm.travelcrm.lead.assignment.history.LeadAssignmentEventRepository;
import com.crm.travelcrm.lead.assignment.history.LeadAssignmentEventType;
import com.crm.travelcrm.lead.alert.LeadAlertAssembler;
import com.crm.travelcrm.lead.alert.LeadAlertBroadcaster;
import com.crm.travelcrm.lead.assignment.service.AssignableUserResolver;
import com.crm.travelcrm.lead.claim.dto.ClaimLeadRequestDto;
import com.crm.travelcrm.lead.claim.dto.LeadAssignmentEventDto;
import com.crm.travelcrm.lead.claim.dto.LeadClaimResultDto;
import com.crm.travelcrm.lead.claim.dto.ReassignLeadRequestDto;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.enums.LeadOriginGroups;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.enums.LeadStageGroups;
import com.crm.travelcrm.lead.exception.LeadClaimLostException;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.lead.service.LeadAccessGuard;
import com.crm.travelcrm.lead.sla.LeadSlaPolicy;
import com.crm.travelcrm.permission.enums.Permission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The claim window: who owns a brand-new lead, and when that stops being negotiable.
 *
 * <p><b>The rule.</b> A new lead is auto-assigned an owner but stays claimable by anyone eligible.
 * Each claim overrides the previous owner. The window shuts the moment first contact is stamped;
 * after that only {@code LEAD_REASSIGN_LOCKED} can move the lead or reopen it.
 *
 * <p><b>The concurrency contract.</b> Every ownership transition here is a single conditional
 * UPDATE on {@code LeadRepository} whose WHERE clause carries the whole precondition. This service
 * never reads a lead, decides, and then writes — that shape has a window between the read and the
 * write in which another transaction commits, and both writers see a valid precondition. Because
 * the check and the write are one statement, two simultaneous claims cannot both succeed: the
 * second matches no row, and its caller is told who won rather than being silently overwritten.
 *
 * <p><b>History is written in THIS transaction</b>, not best-effort like the creation audit. A
 * claim that succeeded with no trail entry is a hole in the only record of who took the lead from
 * whom — and unlike lead creation, there is no more valuable thing here that must survive a
 * bookkeeping failure. If the event cannot be written, the claim is not real either.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LeadClaimService {

    private final LeadRepository leadRepository;
    private final LeadAccessGuard leadAccessGuard;
    private final LeadAssignmentEventRepository eventRepository;
    private final AssignableUserResolver assignableUserResolver;
    private final UserRepository userRepository;
    private final LeadSlaPolicy slaPolicy;
    private final LeadAlertAssembler alertAssembler;
    private final LeadAlertBroadcaster alertBroadcaster;

    /** History notes are a display column; anything longer is a paste accident, not intent. */
    private static final int NOTE_MAX = 255;

    /**
     * Row-level scope key for the claim endpoints. The ACCESS gate is the endpoint's own
     * {@code @PreAuthorize} (LEAD_CLAIM / LEAD_REASSIGN_LOCKED); the row SCOPE reuses the two keys
     * that actually have a configured own/team/all setting. A brand-new permission has no saved
     * scope on any existing user, so scoping by it would silently fall back to a role default that
     * nobody chose.
     */
    private static final String READ_SCOPE_KEY = Permission.LEAD_READ.name();
    private static final String WRITE_SCOPE_KEY = Permission.LEAD_UPDATE.name();

    // ── Claim ────────────────────────────────────────────────────────────────

    /**
     * Take ownership of an open lead, overriding the current owner.
     *
     * @throws LeadClaimLostException 409 when someone moved first (the loser gets the winner's name
     *                                and a fresh version to retry with)
     */
    @Transactional
    public LeadClaimResultDto claim(UUID leadPublicId, ClaimLeadRequestDto request) {
        Long tenantId = requireTenantId();
        User actor = currentUser();

        // Open leads bypass row-scope by design — see LeadAccessGuard.requireVisibleOrClaimable.
        Lead lead = leadAccessGuard.requireVisibleOrClaimable(leadPublicId, READ_SCOPE_KEY);

        if (!LeadOriginGroups.isInbound(lead.getOrigin())) {
            throw new BusinessException(
                    "Only inbound leads can be claimed; manually created leads already have an owner.",
                    HttpStatus.CONFLICT);
        }

        // Holding LEAD_CLAIM is not enough to OWN a lead: the eligible-owner rule is
        // AssignableUserResolver's (active, unlocked, holds LEAD_READ, never a SUB_AGENT), and it is
        // the same rule the auto-assignment engine uses. Re-deriving it here is how the two drift —
        // and the failure mode is a sub-agent owning the parent agency's lead.
        assertMayOwnLeads(tenantId, actor);

        // Claiming a lead you already own is a no-op, NOT an error and NOT a version bump. Two tabs
        // showing the same alert would otherwise fight each other, and bumping the version would
        // invalidate every other client's button for no change of state.
        if (isOwnedBy(lead, actor)) {
            return toResult(lead);
        }

        Long previousOwnerId = ownerId(lead);
        String previousOwnerName = ownerName(lead);

        int updated = leadRepository.claimLead(
                lead.getId(), tenantId, actor, request.getExpectedClaimVersion(),
                LeadOriginGroups.INBOUND_ORIGINS, LeadStageGroups.TERMINAL_STAGES);

        if (updated == 0) {
            throw diagnoseLostRace(leadPublicId, tenantId, false);
        }

        Lead fresh = reload(leadPublicId, tenantId);
        recordEvent(fresh, LeadAssignmentEventType.CLAIMED,
                previousOwnerId, previousOwnerName,
                actor.getId(), actor.getName(),
                actor, request.getNote());

        // Assembled HERE, inside the transaction, while the session can still load the lazy
        // assignedUser and itinerary. The push itself is deferred to after commit.
        alertBroadcaster.broadcastClaimed(tenantId,
                alertAssembler.toAlert(fresh, previousOwnerName, actor.getName()));

        log.info("Lead {} claimed by user {} (was user {}) | tenantId: {}",
                leadPublicId, actor.getId(), previousOwnerId, tenantId);
        return toResult(fresh);
    }

    // ── Contact: close the window ────────────────────────────────────────────

    /**
     * Mark first contact — the lock. Moves the lead to {@code CONTACTED} and freezes the SLA.
     *
     * <p><b>The owner does not change.</b> Whoever presses this may not be the owner (the mockup
     * offers Claim and Mark Contacted as independent actions), and silently transferring the lead
     * would be a surprise. The actor is recorded separately in {@code firstContactedByUserId}, so
     * "contacted by Rakesh while owned by Priya" is visible in the trail rather than hidden by
     * overwriting one with the other.
     */
    @Transactional
    public LeadClaimResultDto markContacted(UUID leadPublicId, String note) {
        Lead lead = leadAccessGuard.requireVisibleOrClaimable(leadPublicId, WRITE_SCOPE_KEY);
        return stampFirstContact(lead, LeadStage.CONTACTED, note);
    }

    /**
     * Stamp first contact and move the lead to {@code targetStage}, closing the claim window.
     *
     * <p>Shared by the "Mark Contacted" button ({@code targetStage = CONTACTED}) and by a Kanban
     * drag into any engaged stage ({@code LeadServiceImpl.updateLeadStage}). Both doors MUST come
     * through here: a drag that set the stage directly would move the lead to "Qualified" while
     * leaving it advertised in the claim feed with the SLA clock still running.
     *
     * <p>Assumes the caller has already resolved and access-checked {@code lead}.
     *
     * @return the resulting claim state, or the unchanged state when the window was already shut and
     *         this was a redundant call from the stage path
     */
    @Transactional
    public LeadClaimResultDto stampFirstContact(Lead lead, LeadStage targetStage, String note) {
        Long tenantId = requireTenantId();
        User actor = currentUser();
        LocalDateTime now = LocalDateTime.now();

        if (!LeadStageGroups.isEngaged(targetStage)) {
            throw new BusinessException(
                    "Stage " + targetStage.getDisplayName() + " does not represent first contact.",
                    HttpStatus.BAD_REQUEST);
        }

        UUID leadPublicId = lead.getPublicId();
        boolean previouslyMeasured = lead.getFirstResponseSeconds() != null;

        int updated;
        if (previouslyMeasured) {
            // Re-contact after a manager reopened the window. The original measurement stands —
            // otherwise reopen-and-recontact would be a way to erase a missed SLA.
            updated = leadRepository.markContactedPreservingSla(
                    lead.getId(), tenantId, now, actor.getId(), targetStage);
        } else {
            long responseSeconds = lead.getCreatedAt() == null
                    ? 0L
                    // Clamped at 0: a clock skew between app servers must not produce a negative
                    // first-response time that then reads as the fastest response of the month.
                    : Math.max(0L, Duration.between(lead.getCreatedAt(), now).getSeconds());
            updated = leadRepository.markContacted(
                    lead.getId(), tenantId, now, actor.getId(), responseSeconds, targetStage);
        }

        if (updated == 0) {
            throw diagnoseLostRace(leadPublicId, tenantId, false);
        }

        Lead fresh = reload(leadPublicId, tenantId);
        recordEvent(fresh, LeadAssignmentEventType.CONTACTED,
                null, null, null, null, actor, note);

        // The lock broadcast is what REMOVES the row from everyone else's claim list. Without it a
        // colleague keeps seeing a lead they can no longer take, and finds out by being refused.
        alertBroadcaster.broadcastLockChanged(tenantId,
                alertAssembler.toAlert(fresh, null, actor.getName()));

        log.info("Lead {} contacted by user {} in {}s (target {}s) | tenantId: {}",
                leadPublicId, actor.getId(), fresh.getFirstResponseSeconds(),
                slaPolicy.effectiveTargetSeconds(fresh), tenantId);
        return toResult(fresh);
    }

    // ── Post-lock supervision ────────────────────────────────────────────────

    /** Move a LOCKED lead to a different owner. {@code LEAD_REASSIGN_LOCKED} only. */
    @Transactional
    public LeadClaimResultDto reassign(UUID leadPublicId, ReassignLeadRequestDto request) {
        Long tenantId = requireTenantId();
        User actor = currentUser();

        // requireVisible, NOT requireVisibleOrClaimable: the lead is locked, so ordinary row-scope
        // applies again. The claim-window widening must not leak into the supervisory path.
        Lead lead = leadAccessGuard.requireVisible(leadPublicId, WRITE_SCOPE_KEY);

        if (LeadAccessGuard.isOpenToClaim(lead)) {
            throw new LeadClaimLostException(
                    "This lead is still open — claim it instead of reassigning it.",
                    LeadClaimLostException.Reason.NOT_LOCKED,
                    leadPublicId, ownerName(lead), ownerPublicId(lead), claimVersion(lead));
        }

        User newOwner = resolveAssignableUser(tenantId, request.getAssignedUserId());
        Long previousOwnerId = ownerId(lead);
        String previousOwnerName = ownerName(lead);

        if (newOwner.getId() == (previousOwnerId == null ? -1L : previousOwnerId)) {
            return toResult(lead);   // already theirs — no write, no history noise
        }

        int updated = leadRepository.reassignLockedLead(
                lead.getId(), tenantId, newOwner, LeadStageGroups.TERMINAL_STAGES);
        if (updated == 0) {
            throw diagnoseLostRace(leadPublicId, tenantId, true);
        }

        Lead fresh = reload(leadPublicId, tenantId);
        recordEvent(fresh, LeadAssignmentEventType.REASSIGNED,
                previousOwnerId, previousOwnerName,
                newOwner.getId(), newOwner.getName(),
                actor, request.getNote());

        // LOCKED, not CLAIMED: the lead is not in anyone's claim list to update. This exists so an
        // open lead-detail view reflects the new owner without a refresh.
        alertBroadcaster.broadcastLockChanged(tenantId,
                alertAssembler.toAlert(fresh, previousOwnerName, actor.getName()));

        log.info("Lead {} reassigned from user {} to user {} by user {} | tenantId: {}",
                leadPublicId, previousOwnerId, newOwner.getId(), actor.getId(), tenantId);
        return toResult(fresh);
    }

    /**
     * Reopen a claim window closed by mistake. {@code LEAD_REASSIGN_LOCKED} only.
     *
     * <p><b>Only from {@code CONTACTED}.</b> This exists for one situation: someone pressed Mark
     * Contacted by accident. A lead that has since moved to Qualified or Proposal Sent is being
     * actively worked, and throwing it back into the claim pool would discard real pipeline state,
     * so that is refused rather than silently reset to New Lead.
     *
     * <p>{@code firstResponseSeconds} survives — see {@code LeadRepository.reopenClaimWindow}.
     */
    @Transactional
    public LeadClaimResultDto reopenClaimWindow(UUID leadPublicId, String note) {
        Long tenantId = requireTenantId();
        User actor = currentUser();

        Lead lead = leadAccessGuard.requireVisible(leadPublicId, WRITE_SCOPE_KEY);

        if (!LeadOriginGroups.isInbound(lead.getOrigin())) {
            throw new BusinessException(
                    "Only inbound leads have a claim window that can be reopened.",
                    HttpStatus.CONFLICT);
        }

        if (LeadAccessGuard.isOpenToClaim(lead)) {
            throw new BusinessException("This lead is already open to claim.", HttpStatus.CONFLICT);
        }
        if (lead.getLeadStage() != LeadStage.CONTACTED) {
            throw new BusinessException(
                    "Only a lead still at Contacted can be reopened — this one has moved to "
                            + lead.getLeadStage().getDisplayName()
                            + ". Reassign it instead.",
                    HttpStatus.CONFLICT);
        }

        int updated = leadRepository.reopenClaimWindow(
                lead.getId(), tenantId, LeadStage.NEW_LEAD, LeadStageGroups.TERMINAL_STAGES);
        if (updated == 0) {
            throw diagnoseLostRace(leadPublicId, tenantId, true);
        }

        Lead fresh = reload(leadPublicId, tenantId);
        recordEvent(fresh, LeadAssignmentEventType.REOPENED,
                null, null, null, null, actor, note);

        // NEW_LEAD, deliberately: the lead has genuinely re-entered the claim pool, and a manager
        // who reopens one wants somebody to pick it up. Re-alerting is the point — a silent
        // reinsertion would leave it sitting in a list nobody is watching.
        alertBroadcaster.broadcastNewLead(tenantId,
                alertAssembler.toAlert(fresh, null, actor.getName()));

        log.info("Lead {} claim window reopened by user {} | tenantId: {}",
                leadPublicId, actor.getId(), tenantId);
        return toResult(fresh);
    }

    // ── History ──────────────────────────────────────────────────────────────

    /** The lead's ownership timeline, oldest first. */
    @Transactional(readOnly = true)
    public List<LeadAssignmentEventDto> history(UUID leadPublicId) {
        Long tenantId = requireTenantId();
        Lead lead = leadAccessGuard.requireVisibleOrClaimable(leadPublicId, READ_SCOPE_KEY);

        return eventRepository
                .findByTenantIdAndLeadIdAndDeletedAtIsNullOrderByIdAsc(tenantId, lead.getId())
                .stream()
                .map(e -> LeadAssignmentEventDto.builder()
                        .id(e.getPublicId())
                        .eventType(e.getEventType() == null ? null : e.getEventType().name())
                        .fromUserName(e.getFromUserName())
                        .toUserName(e.getToUserName())
                        .actorName(e.getActorName())
                        .strategyUsed(e.getStrategyUsed() == null ? null : e.getStrategyUsed().name())
                        .note(e.getNote())
                        .occurredAt(e.getCreatedAt())
                        .build())
                .toList();
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * Work out WHY a conditional UPDATE matched nothing, by re-reading the row. Called only on the
     * losing path, so the extra read costs nothing in the common case.
     *
     * @param expectedLocked true for the supervisory paths (reassign / reopen), which require the
     *                       lead to BE locked — there "still open" is the failure, not the success
     */
    private LeadClaimLostException diagnoseLostRace(UUID leadPublicId, Long tenantId,
                                                    boolean expectedLocked) {
        Lead fresh = leadRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(leadPublicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Lead not found: " + leadPublicId));

        String owner = ownerName(fresh);
        UUID ownerPublicId = ownerPublicId(fresh);
        int version = claimVersion(fresh);

        if (!LeadStageGroups.isActive(fresh.getLeadStage())) {
            return new LeadClaimLostException(
                    "This lead has been marked " + fresh.getLeadStage().getDisplayName() + ".",
                    LeadClaimLostException.Reason.TERMINAL_STAGE,
                    leadPublicId, owner, ownerPublicId, version);
        }
        if (expectedLocked) {
            return new LeadClaimLostException(
                    "This lead is still open — claim it instead.",
                    LeadClaimLostException.Reason.NOT_LOCKED,
                    leadPublicId, owner, ownerPublicId, version);
        }
        if (fresh.getFirstContactedAt() != null) {
            return new LeadClaimLostException(
                    owner == null
                            ? "This lead has already been contacted."
                            : "This lead has already been contacted and is now owned by " + owner + ".",
                    LeadClaimLostException.Reason.ALREADY_CONTACTED,
                    leadPublicId, owner, ownerPublicId, version);
        }
        return new LeadClaimLostException(
                owner == null
                        ? "Someone else claimed this lead first."
                        : "This lead is now owned by " + owner + ".",
                LeadClaimLostException.Reason.VERSION_STALE,
                leadPublicId, owner, ownerPublicId, version);
    }

    /**
     * Reject a caller who holds the claim permission but is not an eligible lead OWNER. Delegates to
     * the assignment engine's own resolver so the two definitions cannot diverge.
     */
    private void assertMayOwnLeads(Long tenantId, User user) {
        boolean eligible = assignableUserResolver.resolve(tenantId, Permission.LEAD_READ).stream()
                .anyMatch(u -> u.getId() == user.getId());
        if (!eligible) {
            throw new BusinessException(
                    "Your account cannot be assigned leads, so it cannot claim one.",
                    HttpStatus.FORBIDDEN);
        }
    }

    /** Validate an explicitly requested new owner: this tenant, active, and eligible to own leads. */
    private User resolveAssignableUser(Long tenantId, UUID userPublicId) {
        User user = userRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(userPublicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userPublicId));
        boolean eligible = assignableUserResolver.resolve(tenantId, Permission.LEAD_READ).stream()
                .anyMatch(u -> u.getId() == user.getId());
        if (!eligible) {
            throw new BusinessException(
                    "This user cannot be assigned leads: " + user.getName(), HttpStatus.BAD_REQUEST);
        }
        return user;
    }

    /**
     * Write the trail entry. In the CALLER'S transaction on purpose — the ownership change and its
     * record are one act here (see the class javadoc).
     */
    private void recordEvent(Lead lead, LeadAssignmentEventType type,
                             Long fromUserId, String fromUserName,
                             Long toUserId, String toUserName,
                             User actor, String note) {
        eventRepository.save(LeadAssignmentEvent.builder()
                .tenantId(lead.getTenantId())
                .leadId(lead.getId())
                .leadPublicId(lead.getPublicId())
                .eventType(type)
                .fromUserId(fromUserId)
                .fromUserName(fromUserName)
                .toUserId(toUserId)
                .toUserName(toUserName)
                .actorUserId(actor == null ? null : actor.getId())
                .actorName(actor == null ? null : actor.getName())
                .note(truncate(note))
                .build());
    }

    private Lead reload(UUID leadPublicId, Long tenantId) {
        return leadRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(leadPublicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Lead not found: " + leadPublicId));
    }

    /** Build the response, resolving the contacted-by user's display fields only when there is one. */
    private LeadClaimResultDto toResult(Lead lead) {
        UUID contactedByPublicId = null;
        String contactedByName = null;
        if (lead.getFirstContactedByUserId() != null) {
            Optional<User> contactedBy = userRepository.findByIdAndTenantIdAndDeletedAtIsNull(
                    lead.getFirstContactedByUserId(), lead.getTenantId());
            contactedByPublicId = contactedBy.map(User::getPublicId).orElse(null);
            contactedByName = contactedBy.map(User::getName).orElse(null);
        }

        return LeadClaimResultDto.builder()
                .leadPublicId(lead.getPublicId())
                .leadCode(lead.getLeadCode())
                .customerName(lead.getCustomerName())
                .ownerPublicId(ownerPublicId(lead))
                .ownerName(ownerName(lead))
                .claimVersion(claimVersion(lead))
                .openToClaim(LeadAccessGuard.isOpenToClaim(lead))
                .firstContactedAt(lead.getFirstContactedAt())
                .firstContactedByPublicId(contactedByPublicId)
                .firstContactedByName(contactedByName)
                .firstResponseSeconds(lead.getFirstResponseSeconds())
                .slaTargetSeconds(slaPolicy.effectiveTargetSeconds(lead))
                .slaBreached(slaPolicy.isBreached(lead, LocalDateTime.now()))
                .leadStage(lead.getLeadStage() == null ? null : lead.getLeadStage().getDisplayName())
                .build();
    }

    /** Null-safe read of the CAS counter — legacy rows carry NULL until the backfill runs. */
    private static int claimVersion(Lead lead) {
        return lead.getClaimVersion() == null ? 0 : lead.getClaimVersion();
    }

    private static boolean isOwnedBy(Lead lead, User user) {
        return lead.getAssignedUser() != null && lead.getAssignedUser().getId() == user.getId();
    }

    private static Long ownerId(Lead lead) {
        return lead.getAssignedUser() == null ? null : lead.getAssignedUser().getId();
    }

    private static String ownerName(Lead lead) {
        return lead.getAssignedUser() == null ? null : lead.getAssignedUser().getName();
    }

    private static UUID ownerPublicId(Lead lead) {
        return lead.getAssignedUser() == null ? null : lead.getAssignedUser().getPublicId();
    }

    private static String truncate(String note) {
        if (note == null) return null;
        String trimmed = note.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() <= NOTE_MAX ? trimmed : trimmed.substring(0, NOTE_MAX);
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty for a lead claim action.");
        }
        return tenantId;
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User u) {
            return u;
        }
        throw new IllegalStateException("No tenant user in security context");
    }
}
