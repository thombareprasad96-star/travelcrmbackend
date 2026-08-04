package com.crm.travelcrm.lead.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.event.LeadSoftDeletedEvent;
import com.crm.travelcrm.customer.dto.response.CustomerMatchResponse;
import com.crm.travelcrm.customer.entity.Customer;
import com.crm.travelcrm.customer.service.CustomerMatcher;
import com.crm.travelcrm.lead.dto.CreateLeadRequestDto;
import com.crm.travelcrm.lead.dto.LeadBoardColumnDto;
import com.crm.travelcrm.lead.dto.LeadResponseDto;
import com.crm.travelcrm.lead.dto.UserLeadStageCountDto;
import com.crm.travelcrm.lead.dto.UserWorkloadDto;
import com.crm.travelcrm.lead.alert.LeadAlertAssembler;
import com.crm.travelcrm.lead.alert.LeadAlertBroadcaster;
import com.crm.travelcrm.lead.assignment.audit.LeadAssignmentAudit;
import com.crm.travelcrm.lead.assignment.audit.LeadAssignmentAuditRecorder;
import com.crm.travelcrm.lead.assignment.history.LeadAssignmentEvent;
import com.crm.travelcrm.lead.assignment.history.LeadAssignmentEventRecorder;
import com.crm.travelcrm.lead.assignment.history.LeadAssignmentEventType;
import com.crm.travelcrm.lead.assignment.service.AssignableUserResolver;
import com.crm.travelcrm.lead.assignment.service.AssignmentOutcome;
import com.crm.travelcrm.lead.assignment.service.LeadAssignmentService;
import com.crm.travelcrm.lead.claim.service.LeadClaimService;
import com.crm.travelcrm.lead.sla.LeadSlaPolicy;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.entity.LeadItinerary;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.ingest.IngestPolicy;
import com.crm.travelcrm.lead.ingest.LeadActor;
import com.crm.travelcrm.lead.enums.LeadStageGroups;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.common.exception.RestoreAvailableException;
import com.crm.travelcrm.lead.exception.DuplicateLeadException;
import com.crm.travelcrm.lead.exception.LeadQuotaExceededException;
import com.crm.travelcrm.lead.exception.NoEligibleAssigneeException;
import com.crm.travelcrm.lead.mapper.LeadMapper;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.lead.util.LeadCodeGenerator;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.permission.enums.Permission;
import com.crm.travelcrm.permission.service.ScopeResolver;
import com.crm.travelcrm.permission.service.SubAgentScope;
import com.crm.travelcrm.quotation.dto.QuotationRefDto;
import com.crm.travelcrm.quotation.service.QuotationService;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class LeadServiceImpl implements LeadService {

    private final LeadRepository leadRepository;
    private final LeadMapper     leadMapper;
    private final LeadCodeGenerator leadCodeGenerator;
    private final ApplicationEventPublisher eventPublisher;
    private final UserRepository userRepository;
    private final QuotationService quotationService;
    private final ScopeResolver scopeResolver;
    private final SubAgentScope subAgentScope;
    private final LeadAccessGuard leadAccessGuard;
    private final TenantRepository tenantRepository;
    private final LeadAssignmentService leadAssignmentService;
    private final LeadAssignmentAuditRecorder leadAssignmentAuditRecorder;
    private final LeadAssignmentEventRecorder leadAssignmentEventRecorder;
    private final LeadClaimService leadClaimService;
    private final LeadSlaPolicy slaPolicy;
    private final LeadAlertAssembler alertAssembler;
    private final LeadAlertBroadcaster alertBroadcaster;
    private final AssignableUserResolver assignableUserResolver;
    private final CustomerMatcher customerMatcher;

    // Terminal (closed) stages. A lead here no longer blocks a fresh lead for the same
    // contact, and CONVERTED specifically is owned by the booking lifecycle — it may be
    // entered ONLY by convert-to-booking and left ONLY by cancelling that booking (see
    // assertConversionStageTransitionAllowed). Sourced from LeadStageGroups so the
    // duplicate-detection logic, the load-based assignment feature, and the uq_leads_*_open
    // partial unique indexes in db/indexes.sql all share one definition.
    private static final Set<LeadStage> TERMINAL_STAGES = LeadStageGroups.TERMINAL_STAGES;

    // ── Create ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public LeadResponseDto createLead(CreateLeadRequestDto request) {
        return createLead(request, LeadActor.human(currentUser()), IngestPolicy.INTERACTIVE);
    }

    @Override
    @Transactional
    public LeadResponseDto createLead(CreateLeadRequestDto request, LeadActor actor,
                                      IngestPolicy policy) {
        Long tenantId = currentTenantId();
        enforceLeadQuota(tenantId);

        // Deliberately no email/phone: INFO goes to a retained rolling file in prod, and a CRM must
        // not scatter customer contact details across its logs. tenantId + actor is what an operator
        // actually needs to trace this; the lead code is logged on the success line below.
        log.info("Creating lead | tenantId: {} | actor: {}", tenantId, actor);

        validateNoDuplicates(request, tenantId, null);
        // Active duplicates errored above; a match that lives ONLY in Trash becomes a
        // structured "restore available" response instead of a dead "already exists".
        checkTrashedForRestore(request, tenantId);

        Lead lead = leadMapper.toEntity(request, actor);
        // tenantId is set here so it's available immediately in this transaction;
        // TenantEntityListener also guards it on @PrePersist as a safety net.
        lead.setTenantId(tenantId);

        // Human-readable reference (LD-26-0001). Assigned HERE rather than in the mapper because the
        // generator holds a pessimistic lock on the tenant's counter row that must be held to commit
        // — this method's @Transactional is that unit. Every creation path (manual, inbound ingest,
        // sub-agent portal) funnels through here, so none of them can mint a code-less lead.
        lead.setLeadCode(leadCodeGenerator.generate(tenantId));

        // ── Claim window opens here ──────────────────────────────────────────
        // Explicit 0 rather than relying on a column default: the CAS compares against the value the
        // client last saw, and a row that starts NULL would depend on the backfill in db/indexes.sql
        // having run before its first claim. New rows must never need a backfill to work.
        lead.setClaimVersion(0);
        // The SLA target is PINNED at creation, never read live afterwards — raising the target next
        // month must not retroactively un-breach this lead. See LeadSlaPolicy.
        lead.setSlaTargetSeconds(slaPolicy.targetSecondsForNewLead(tenantId));

        // Intelligent assignment (Strategy Pattern). Runs inside this transaction so the round-robin
        // cursor's pessimistic lock is held to commit — which is why assignment happens HERE and is
        // not resolved by the caller and passed in.
        AssignmentOutcome outcome = switch (policy) {
            // A privileged creator's explicit choice is honoured while the load-based recommendation
            // is recomputed for the audit; agents/staff/sub-agents are force-assigned to themselves.
            case INTERACTIVE -> leadAssignmentService.assignForCreate(request.getAssignedUserId());

            // No security context and no requester, so assignForCreate cannot run — it opens with
            // currentUser(). request.getAssignedUserId() is deliberately IGNORED: an adapter parses
            // bytes and must never get to choose a tenant's user.
            case MACHINE -> leadAssignmentService.assignForInbound(tenantId)
                    .orElseThrow(() -> new NoEligibleAssigneeException(tenantId));
        };
        lead.setAssignedUser(outcome.finalUser());

        // ── Existing-customer match + auto-prefill ───────────────────────────
        // Runs on EVERY creation path — manual form, inbound integration, sub-agent portal — because
        // it sits inside the single create method rather than in the controller. An IVR-sourced lead
        // for a customer of eight years should link just as reliably as a typed one.
        CustomerMatchResponse customerMatch = linkExistingCustomer(lead, tenantId);

        Lead savedLead = leadRepository.save(lead);
        log.info("Lead created | id: {} | tenantId: {} | strategy: {} | override: {}",
                savedLead.getPublicId(), tenantId, outcome.strategyUsed(), outcome.manualOverride());

        // ORDER IS LOAD-BEARING: audit BEFORE publish. NotifyEventListener is a plain synchronous
        // @EventListener that clears TenantContext in its finally block, on THIS thread — so
        // anything after a publish runs with a null context, and TenantFilterAspect fails OPEN on a
        // null tenant (returns without enabling the filter, no error, no log). Publishing first left
        // the audit's REQUIRES_NEW write unfiltered; it survived only on the explicit .tenantId()
        // below. Publish last, and keep it last.
        recordAssignmentAudit(savedLead, outcome, tenantId, actor);
        recordAssignmentHistory(savedLead, outcome, tenantId, actor);

        // The tenant-wide alert. Assembled NOW, while the session can still resolve the lazy
        // assignedUser and itinerary; the push itself is deferred to after commit, so a rollback
        // can never leave every browser in the tenant showing a lead that does not exist.
        // Registered BEFORE the publish below for the same reason the audit is: publishing clears
        // TenantContext on this thread, and anything after it runs without a tenant.
        alertBroadcaster.broadcastNewLead(tenantId, alertAssembler.toAlert(savedLead));

        publishLeadCreatedNotification(savedLead, tenantId);

        LeadResponseDto response = leadMapper.toResponse(savedLead);
        // The popup signal rides on the CREATE response only. Deliberately not set by toResponse():
        // re-attaching it to every subsequent read would make the frontend re-raise "Customer found"
        // each time the lead is opened, long after the clerk has acknowledged it once.
        response.setCustomerMatch(customerMatch);
        return response;
    }

    /**
     * The one rule this feature exists for: <b>if a customer already exists with the same email OR
     * the same phone number, link the lead to them and fill in what the form left blank.</b>
     *
     * <p>Done here — server-side, inside the creation transaction — rather than in the browser,
     * because a client-side prefill is only as reliable as the clerk's connection and can be skipped
     * entirely by anything that is not the form (the inbound integrations, the sub-agent portal, a
     * direct API call). Doing it at the point of persistence is what makes the link a property of
     * the data instead of a property of the UI.
     *
     * <p><b>Prefill never overwrites.</b> Only fields the request left empty are filled. The person
     * typing has just spoken to the customer; the stored profile may be years old. A "prefill" that
     * clobbers the email the clerk was given on the phone is a data-loss bug wearing a helpful hat.
     * The returned {@code prefilledFields} names exactly what was touched, so the popup can say so.
     *
     * @return the popup payload when a customer matched, else {@code null} (no popup)
     */
    private CustomerMatchResponse linkExistingCustomer(Lead lead, Long tenantId) {
        Optional<CustomerMatcher.Match> found =
                customerMatcher.find(lead.getPhone(), lead.getEmail(), tenantId);
        if (found.isEmpty()) {
            // Stamp the probe even on a MISS. Without this, "we looked and nobody matched"
            // (customerId null, customerLinkedAt set) is indistinguishable from "never looked"
            // (both null) — the distinction Lead.customerId's javadoc promises this field makes.
            // Rows written before this change keep both null and so still read as never checked,
            // which is exactly what they were.
            lead.setCustomerLinkedAt(LocalDateTime.now());
            return null;   // ordinary outcome — a brand-new contact, no popup
        }

        CustomerMatcher.Match match = found.get();
        Customer customer = match.customer();

        lead.setCustomerId(customer.getId());
        lead.setCustomerPublicId(customer.getPublicId());
        lead.setCustomerLinkedAt(LocalDateTime.now());

        List<String> prefilled = new ArrayList<>();
        // Fill-if-empty, field by field. Phone is never touched: it is the identifier the clerk
        // just keyed in and the per-tenant natural key on both sides — rewriting it would silently
        // re-point the lead at a different person than the one on the phone.
        if (isBlank(lead.getCustomerName()) && !isBlank(customer.getName())) {
            lead.setCustomerName(customer.getName());
            prefilled.add("customerName");
        }
        if (isBlank(lead.getEmail()) && !isBlank(customer.getEmail())) {
            lead.setEmail(customer.getEmail().toLowerCase());
            prefilled.add("email");
        }
        if (lead.getBirthDate() == null && customer.getBirthday() != null) {
            lead.setBirthDate(customer.getBirthday());
            prefilled.add("birthDate");
        }
        if (lead.getAnniversaryDate() == null && customer.getAnniversary() != null) {
            lead.setAnniversaryDate(customer.getAnniversary());
            prefilled.add("anniversaryDate");
        }
        if (lead.getPreferredCommunication() == null && customer.getCommPref() != null) {
            lead.setPreferredCommunication(customer.getCommPref());
            prefilled.add("preferredCommunication");
        }
        // departCity is the traveller's origin city and the customer's own city is the best default
        // for it. "Not Specified" counts as blank — that is the literal the form sends for an
        // untouched field, so treating it as a real value would block the prefill on every lead.
        if (isUnset(lead.getDepartCity()) && !isBlank(customer.getCity())) {
            lead.setDepartCity(customer.getCity());
            prefilled.add("departCity");
        }

        log.info("Lead linked to existing customer {} (matched on {}) | prefilled: {} | tenantId: {}",
                customer.getCustomerCode(), match.matchedOn(), prefilled, tenantId);

        return customerMatcher.toResponse(match, tenantId, prefilled);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Blank, or the "Not Specified" placeholder the lead form sends for an untouched origin field. */
    private static boolean isUnset(String value) {
        return isBlank(value) || "Not Specified".equalsIgnoreCase(value.trim());
    }

    /**
     * Best-effort audit of how the lead's owner was decided (createdBy, recommendedUser,
     * finalAssignedUser, strategyUsed, manualOverride, timestamp). Written in its own transaction by
     * {@link LeadAssignmentAuditRecorder} so it can never roll back the creation.
     *
     * <p>The actor is passed in rather than read from the security context: this used to call
     * {@code currentUser()} on the line ABOVE the try, so on a context-less thread it threw and
     * rolled back the very lead the comment below promises it cannot break. It also made the whole
     * method unreachable for any non-human caller.
     */
    private void recordAssignmentAudit(Lead lead, AssignmentOutcome outcome, Long tenantId,
                                       LeadActor actor) {
        try {
            // record() runs in its own REQUIRES_NEW transaction (cross-bean call = proxy applies), so
            // swallowing a failure here leaves this lead-creation transaction intact — an audit
            // problem must never break the actual lead creation.
            leadAssignmentAuditRecorder.record(LeadAssignmentAudit.builder()
                    .tenantId(tenantId)
                    .leadId(lead.getId())
                    .leadPublicId(lead.getPublicId())
                    // Null for INTEGRATION / SYSTEM — there is no user behind them. displayName
                    // carries the connection label or the system reason instead, so the trail still
                    // reads "who did this" for a human six months later.
                    .createdByUserId(actor.userId())
                    .createdByName(actor.displayName())
                    .recommendedUserId(outcome.recommendedUserId())
                    .recommendedUserName(outcome.recommendedUserName())
                    .finalAssignedUserId(outcome.finalUser().getId())
                    .finalAssignedUserName(outcome.finalUser().getName())
                    .strategyUsed(outcome.strategyUsed())
                    .manualOverride(outcome.manualOverride())
                    .build());
        } catch (Exception ex) {
            log.warn("Lead assignment audit write failed for lead {}: {}",
                    lead.getPublicId(), ex.getMessage());
        }
    }

    /**
     * Open the lead's ownership TIMELINE with its first entry. Sibling of
     * {@link #recordAssignmentAudit} and deliberately a separate row in a separate table: that one
     * records the DECISION (what the strategy recommended vs what happened), this records the
     * resulting OWNERSHIP, and every later claim appends here. Folding them together would force
     * each claim to invent a recommendation it never had.
     *
     * <p>Best-effort, same contract as the audit: written by a {@code REQUIRES_NEW} recorder across
     * a bean boundary and wrapped in try/catch, so a history failure can never roll back the lead.
     * The claim-time events are the opposite (in-transaction) — see {@code LeadClaimService}.
     */
    private void recordAssignmentHistory(Lead lead, AssignmentOutcome outcome, Long tenantId,
                                         LeadActor actor) {
        try {
            leadAssignmentEventRecorder.record(LeadAssignmentEvent.builder()
                    .tenantId(tenantId)
                    .leadId(lead.getId())
                    .leadPublicId(lead.getPublicId())
                    .eventType(LeadAssignmentEventType.AUTO_ASSIGNED)
                    // No fromUser — nobody held it before. A self-referential "from X to X" row would
                    // render in the timeline as a transfer that never happened.
                    .toUserId(outcome.finalUser().getId())
                    .toUserName(outcome.finalUser().getName())
                    // Null for INTEGRATION / SYSTEM: a webhook has no user behind it, and actorName
                    // carries the connection label instead so the trail still reads "who did this".
                    .actorUserId(actor.userId())
                    .actorName(actor.displayName())
                    .strategyUsed(outcome.strategyUsed())
                    .build());
        } catch (Exception ex) {
            log.warn("Lead assignment history write failed for lead {}: {}",
                    lead.getPublicId(), ex.getMessage());
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<LeadResponseDto> getAllLeads(int page, int size,
                                             String sortBy, String sortDir) {
        Long tenantId = currentTenantId();

        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page, size, sort);

        // Tenant + soft-delete, then row-level scope (own / team / all) for this user.
        Set<Long> visibleIds = scopeResolver.visibleUserIds(currentUser(), "LEAD_READ");
        Page<Lead> leadPage;
        if (visibleIds == null) {                 // ALL — no owner restriction
            leadPage = leadRepository.findAllByTenantIdAndDeletedAtIsNull(tenantId, pageable);
        } else if (visibleIds.isEmpty()) {        // NONE — sees nothing
            leadPage = Page.empty(pageable);
        } else {                                  // OWN / TEAM — owner_id IN (visibleIds)
            leadPage = leadRepository.findAllByTenantIdAndDeletedAtIsNullAndAssignedUser_IdIn(
                    tenantId, visibleIds, pageable);
        }

        List<Lead> leads = leadPage.getContent();

        List<LeadResponseDto> dtos = leads.stream()
                .map(leadMapper::toResponse)
                .collect(Collectors.toList());
        // One batch query for the latest quotation of every lead on this page (no N+1).
        enrichWithLatestQuotation(dtos);
        enrichWithLogCounts(dtos, leads);

        return new PageImpl<>(dtos, pageable, leadPage.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public LeadResponseDto getLeadById(UUID publicId) {
        // Tenant + row-level scope enforced centrally — never expose the internal Long id.
        Lead lead = leadAccessGuard.requireVisible(publicId, "LEAD_READ");

        LeadResponseDto dto = leadMapper.toResponse(lead);
        dto.setLatestQuotation(quotationService.getLatestRefByLead(dto.getId()));
        enrichWithLogCounts(List.of(dto), List.of(lead));
        return dto;
    }

    @Override
    @Transactional(readOnly = true)
    public LeadResponseDto searchLead(String keyword) {
        Long tenantId = currentTenantId();
        Lead lead;

        if (keyword.contains("@")) {
            lead = leadRepository
                    .findFirstByEmailAndTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                            keyword.toLowerCase(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Lead not found with email: " + keyword));
        } else {
            lead = leadRepository
                    .findFirstByPhoneAndTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                            keyword, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Lead not found with phone: " + keyword));
        }

        // Row-level scope: search must not let an own/team-scoped user pull any tenant
        // lead by email/phone. Report not-found rather than reveal a lead outside scope.
        if (!scopeResolver.canSee(currentUser(), "LEAD_READ", ownerId(lead))) {
            throw new ResourceNotFoundException("Lead not found: " + keyword);
        }

        LeadResponseDto dto = leadMapper.toResponse(lead);
        enrichWithLogCounts(List.of(dto), List.of(lead));
        return dto;
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public LeadResponseDto updateLead(UUID publicId, CreateLeadRequestDto request) {
        Long tenantId = currentTenantId();

        // Tenant + row-level scope (LEAD_UPDATE) enforced centrally before any mutation.
        Lead lead = leadAccessGuard.requireVisible(publicId, "LEAD_UPDATE");

        // Duplicate check excluding self
        validateNoDuplicates(request, tenantId, publicId);

        // Basic details
        lead.setCustomerName(request.getCustomerName());
        // Lowercase to match create + duplicate checks — otherwise an updated
        // email can dodge the tenant-scoped uniqueness validation.
        // Null-guarded since leads.email became nullable; the DTO's @NotBlank only covers callers
        // that go through @Valid, which the machine path does not.
        lead.setEmail(request.getEmail() == null ? null : request.getEmail().toLowerCase());
        lead.setPhone(request.getPhone());
        // The shadow canonical key must move with the raw phone or it silently rots: an edited phone
        // would keep matching on the OLD canonical value, and the append-on-repeat path would attach
        // a new enquiry to the wrong lead. Same derivation as create — LeadMapper owns it.
        lead.setPhoneNormalized(leadMapper.canonicalPhone(request.getPhone()));
        lead.setLeadType(request.getLeadType());
        lead.setLeadSource(request.getLeadSource());
        // Editing a lead must not smuggle it into/out of CONVERTED behind the booking's back.
        assertConversionStageTransitionAllowed(lead.getLeadStage(), request.getLeadStage());
        lead.setLeadStage(request.getLeadStage());
        lead.setNotes(request.getNotes());

        // Assignment & personal
        lead.setAssignedUser(resolveAssignedUser(request.getAssignedUserId(), tenantId));
        lead.setBirthDate(request.getBirthDate());
        lead.setAnniversaryDate(request.getAnniversaryDate());
        lead.setPreferredCommunication(request.getPreferredCommunication());
        lead.setFollowUpDate(request.getFollowUpDate());

        // Travel details
        lead.setPackageType(request.getPackageType());
        lead.setTravelDate(request.getTravelDate());
        lead.setBudget(request.getBudget());
        lead.setDepartCountry(request.getDepartCountry());
        lead.setDepartCity(request.getDepartCity());

        // Departure / transport. Every one of these has to be assigned unconditionally, including
        // to null: the mode can be switched from Flight to Train on an edit, and skipping the
        // now-irrelevant setters would leave the old airport on the row forever.
        lead.setDepartureMode(request.getDepartureMode());
        lead.setDepartureAirport(request.getDepartureAirport());
        lead.setAirportCode(request.getAirportCode() == null
                ? null : request.getAirportCode().trim().toUpperCase());
        lead.setPreferredFlightTime(request.getPreferredFlightTime());
        lead.setRailwayStation(request.getRailwayStation());
        lead.setTrainClass(request.getTrainClass());
        lead.setPreferredTrainTime(request.getPreferredTrainTime());
        lead.setPickupAddress(request.getPickupAddress());
        lead.setPickupDateTime(request.getPickupDateTime());
        lead.setVehiclePreference(request.getVehiclePreference());
        // Same rule create applies — drop whatever does not belong to the selected mode.
        leadMapper.clearUnusedTransport(lead);

        // Accessibility
        lead.setSpecialAssistanceRequired(request.getSpecialAssistanceRequired());
        lead.setAssistancePassengerCount(request.getAssistancePassengerCount());
        lead.setSpecialAssistanceNotes(request.getSpecialAssistanceNotes());
        // Mutate the managed collection, never replace it (same rule as services below).
        lead.getSpecialAssistanceTypes().clear();
        if (request.getSpecialAssistanceTypes() != null) {
            lead.getSpecialAssistanceTypes().addAll(request.getSpecialAssistanceTypes());
        }
        leadMapper.clearUnusedAssistance(lead);

        // Passenger counts
        lead.setRooms(request.getRooms());
        lead.setAdults(leadMapper.resolveAdults(request));
        lead.setMale(request.getMale());
        lead.setFemale(request.getFemale());
        lead.setChildren(request.getChildren());
        lead.setInfants(request.getInfants());
        lead.setExtraBeds(request.getExtraBeds());

        // Services — mutate the Hibernate-managed collection, never replace it
        lead.getServices().clear();
        if (request.getServices() != null) {
            lead.getServices().addAll(request.getServices());
        }

        // Itinerary — clear and rebuild
        if (request.getItinerary() != null) {
            lead.getItinerary().clear();
            request.getItinerary().forEach(item -> {
                LeadItinerary itinerary = new LeadItinerary();
                itinerary.setDestination(item.getDestination());
                itinerary.setCity(item.getCity());
                itinerary.setNights(item.getNights());
                itinerary.setDayNumber(item.getDayNumber());
                itinerary.setDestinationId(item.getDestinationId());
                itinerary.setCityId(item.getCityId());
                itinerary.setLead(lead);
                lead.getItinerary().add(itinerary);
            });
        }

        Lead updated = leadRepository.save(lead);
        log.info("Lead updated | publicId: {} | tenantId: {}", publicId, tenantId);
        eventPublisher.publishEvent(NotifyEvent.builder()
                .type("LEAD_UPDATED")
                .tenantId(tenantId)
                .actorUserId(currentUserId())
                .title("Lead Updated: " + updated.getCustomerName())
                .message("Lead " + updated.getCustomerName() + " was updated")
                .referenceType("LEAD")
                .referencePublicId(updated.getPublicId())
                .channels(Set.of(DeliveryChannel.IN_APP))
                .build());
        return leadMapper.toResponse(updated);
    }

    // ── Delete (soft) ─────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void deleteLead(UUID publicId) {
        Long tenantId = currentTenantId();

        // Tenant + row-level scope (LEAD_DELETE) enforced centrally.
        Lead lead = leadAccessGuard.requireVisible(publicId, "LEAD_DELETE");

        // Soft delete — uses BaseEntity.softDelete()
        lead.softDelete(currentUserEmail());

        leadRepository.save(lead);
        log.info("Lead soft-deleted | publicId: {} | tenantId: {}", publicId, tenantId);

        // Cascade: let the quotation module soft-delete this lead's quotations.
        // Published in-transaction so a synchronous listener participates in the same
        // commit (atomic cascade). The event lives in common — no cross-module import.
        eventPublisher.publishEvent(new LeadSoftDeletedEvent(lead.getId(), tenantId));
    }

    // ── Kanban board ────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<LeadBoardColumnDto> getLeadBoard() {
        Long tenantId = currentTenantId();

        // One query, assignee eagerly joined; row-level scope applied; grouped by stage.
        Set<Long> visibleIds = scopeResolver.visibleUserIds(currentUser(), "LEAD_READ");
        List<Lead> leads;
        if (visibleIds == null) {
            leads = leadRepository.findAllByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(tenantId);
        } else if (visibleIds.isEmpty()) {
            leads = List.of();
        } else {
            leads = leadRepository.findAllByTenantIdAndDeletedAtIsNullAndAssignedUser_IdInOrderByCreatedAtDesc(
                    tenantId, visibleIds);
        }
        Map<LeadStage, List<Lead>> byStage = leads.stream()
                .collect(Collectors.groupingBy(Lead::getLeadStage));

        // Emit every stage in pipeline order, including empty columns, so the
        // board always renders all seven lanes.
        List<LeadBoardColumnDto> columns = Arrays.stream(LeadStage.values())
                .map(stage -> {
                    List<Lead> stageLeads = byStage.getOrDefault(stage, List.of());
                    BigDecimal total = stageLeads.stream()
                            .map(Lead::getBudget)
                            .filter(Objects::nonNull)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    return LeadBoardColumnDto.builder()
                            .stage(stage)
                            .count(stageLeads.size())
                            .totalValue(total)
                            .leads(stageLeads.stream().map(leadMapper::toResponse).toList())
                            .build();
                })
                .toList();

        // One batch quotation query for every lead across all columns (no N+1).
        List<LeadResponseDto> boardDtos = columns.stream()
                .flatMap(c -> c.getLeads().stream())
                .toList();
        enrichWithLatestQuotation(boardDtos);
        enrichWithLogCounts(boardDtos, leads);

        return columns;
    }

    /**
     * Batch-populate {@code latestQuotation} on the given lead DTOs using a single
     * quotation query (avoids one query per lead). Mutates the DTOs in place; leads
     * with no quotation are left null.
     */
    private void enrichWithLatestQuotation(List<LeadResponseDto> dtos) {
        if (dtos.isEmpty()) return;
        List<UUID> leadIds = dtos.stream().map(LeadResponseDto::getId).toList();
        Map<UUID, QuotationRefDto> latest = quotationService.getLatestRefsByLeads(leadIds);
        if (latest.isEmpty()) return;
        dtos.forEach(dto -> dto.setLatestQuotation(latest.get(dto.getId())));
    }

    /**
     * Batch-populate logCount on lead DTOs using one grouped count query.
     * Counts are matched through the public id exposed on the DTO.
     */
    private void enrichWithLogCounts(List<LeadResponseDto> dtos, List<Lead> leads) {
        if (dtos.isEmpty()) return;

        List<Long> leadIds = leads.stream()
                .map(Lead::getId)
                .filter(Objects::nonNull)
                .toList();

        Map<Long, Long> countsByLeadId = leadIds.isEmpty()
                ? Map.of()
                : leadRepository.countLogsByLeadIds(leadIds).stream()
                .collect(Collectors.toMap(
                        row -> ((Number) row[0]).longValue(),
                        row -> ((Number) row[1]).longValue()));

        Map<UUID, Long> countsByPublicId = leads.stream()
                .filter(lead -> lead.getPublicId() != null)
                .collect(Collectors.toMap(
                        Lead::getPublicId,
                        lead -> countsByLeadId.getOrDefault(lead.getId(), 0L),
                        (first, second) -> first));

        dtos.forEach(dto -> dto.setLogCount(
                countsByPublicId.getOrDefault(dto.getId(), 0L)));
    }

    @Override
    @Transactional
    public LeadResponseDto updateLeadStage(UUID publicId, LeadStage newStage) {
        Long tenantId = currentTenantId();

        // Tenant + row-level scope (LEAD_UPDATE) — a drag-and-drop stage change is a mutation.
        Lead lead = leadAccessGuard.requireVisible(publicId, "LEAD_UPDATE");

        LeadStage oldStage = lead.getLeadStage();
        if (oldStage == newStage) {
            // No-op move (dropped back into the same column) — skip the write
            // and the notification entirely.
            return leadMapper.toResponse(lead);
        }

        // CONVERTED is owned by the booking lifecycle — reject drag/edit crossings.
        assertConversionStageTransitionAllowed(oldStage, newStage);

        // ── The claim window's SECOND door ───────────────────────────────────
        // Dragging an open lead into an engaged stage IS first contact, and it must close the claim
        // window exactly as the "Mark Contacted" button does. Routing it through the same CAS rather
        // than setting the stage here is the whole point: a direct save would move the lead to
        // "Qualified" while leaving it advertised in the claim feed with its SLA clock still running,
        // and two people would keep being able to take it. The CAS also writes the stage, so this
        // branch must NOT fall through to the save below.
        if (LeadAccessGuard.isOpenToClaim(lead) && LeadStageGroups.isEngaged(newStage)) {
            leadClaimService.stampFirstContact(lead, newStage, "Stage moved to "
                    + newStage.getDisplayName());
            // Re-read: stampFirstContact runs a bulk UPDATE that clears the persistence context, so
            // the `lead` reference above is now stale and would report the pre-contact state.
            Lead contacted = leadRepository
                    .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Lead not found: " + publicId));
            log.info("Lead stage changed with first-contact stamp | publicId: {} | {} -> {} | tenantId: {}",
                    publicId, oldStage, newStage, tenantId);
            publishStageChangedNotification(contacted, oldStage, newStage, tenantId);
            return leadMapper.toResponse(contacted);
        }

        lead.setLeadStage(newStage);
        Lead updated = leadRepository.save(lead);
        log.info("Lead stage changed | publicId: {} | {} -> {} | tenantId: {}",
                publicId, oldStage, newStage, tenantId);

        publishStageChangedNotification(updated, oldStage, newStage, tenantId);

        return leadMapper.toResponse(updated);
    }

    /**
     * Extracted so BOTH stage-change paths — the ordinary save and the first-contact CAS above —
     * raise the identical event. Inlined in one branch only, it silently went missing whenever a
     * drag happened to close the claim window, which is precisely the move a manager most wants to
     * see in the feed.
     */
    private void publishStageChangedNotification(Lead lead, LeadStage oldStage, LeadStage newStage,
                                                 Long tenantId) {
        eventPublisher.publishEvent(NotifyEvent.builder()
                .type("LEAD_STAGE_CHANGED")
                .tenantId(tenantId)
                .actorUserId(currentUserId())
                .title("Lead moved: " + lead.getCustomerName())
                .message(lead.getCustomerName() + " moved from "
                        + oldStage.getDisplayName() + " to " + newStage.getDisplayName())
                .referenceType("LEAD")
                .referencePublicId(lead.getPublicId())
                .channels(Set.of(DeliveryChannel.IN_APP))
                .build());
    }

    /**
     * Guard the CONVERTED invariant. CONVERTED is owned by the booking lifecycle, not by
     * ad-hoc stage edits: it is ENTERED only via convert-to-booking (which also creates the
     * booking and the back-links) and LEFT only via cancelling that booking (which sets
     * REOPENED and clears the back-links). Blocking both crossings on the generic
     * stage/edit endpoints stops us ever producing a CONVERTED lead with no booking, or a
     * live lead still pointing at one. A CONVERTED→CONVERTED no-op (editing other fields of
     * a converted lead) is allowed.
     */
    private void assertConversionStageTransitionAllowed(LeadStage oldStage, LeadStage newStage) {
        if (oldStage == newStage) return;
        if (newStage == LeadStage.CONVERTED) {
            throw new BusinessException(
                    "A lead becomes Converted only by converting it to a booking.",
                    HttpStatus.CONFLICT);
        }
        if (oldStage == LeadStage.CONVERTED) {
            throw new BusinessException(
                    "This lead is already converted to a booking. Cancel the booking to reopen it.",
                    HttpStatus.CONFLICT);
        }
    }

    // ── Statistics ────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public long getLeadCountForUser(UUID userPublicId) {
        Long tenantId = currentTenantId();
        // Validates the user exists in this tenant — a foreign/unknown id
        // returns 404, not a silently wrong 0
        User target = resolveAssignedUserForStats(userPublicId, tenantId);
        // Row-level scope: an own/team-scoped user can't read another user's lead count.
        if (!scopeResolver.canSee(currentUser(), "LEAD_READ", target.getId())) {
            throw new ResourceNotFoundException("User not found: " + userPublicId);
        }
        return leadRepository
                .countByAssignedUserPublicIdAndTenantIdAndDeletedAtIsNull(userPublicId, tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserWorkloadDto> getUserWorkload() {
        Long tenantId = currentTenantId();
        Set<Long> visibleIds = scopeResolver.visibleUserIds(currentUser(), "LEAD_READ");
        if (visibleIds == null)        return leadRepository.findUserWorkload(tenantId);
        if (visibleIds.isEmpty())      return List.of();
        return leadRepository.findUserWorkloadForUsers(tenantId, visibleIds);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserLeadStageCountDto> getLeadStageBreakdownPerUser() {
        Long tenantId = currentTenantId();
        Set<Long> visibleIds = scopeResolver.visibleUserIds(currentUser(), "LEAD_READ");
        if (visibleIds == null)        return leadRepository.countLeadsByStagePerUser(tenantId);
        if (visibleIds.isEmpty())      return List.of();
        return leadRepository.countLeadsByStagePerUserForUsers(tenantId, visibleIds);
    }

    /** Existence check only — unlike assignment, inactive users are fine here. */
    private User resolveAssignedUserForStats(UUID userPublicId, Long tenantId) {
        return userRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(userPublicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found: " + userPublicId));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Resolve tenantId from TenantContext (set by JwtAuthFilter).
     * Fail fast if missing — means filter chain is misconfigured.
     */
    private Long currentTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "TenantContext is empty. Ensure JwtAuthFilter is running " +
                            "and the JWT contains a tenantId claim.");
        }
        return tenantId;
    }

    /**
     * Plan lead cap — blocks creating leads beyond the tenant's allowance ({@code Tenant.maxLeads},
     * denormalized from the plan; null = unlimited). Mirrors the user-seat check. Pre-existing
     * tenants carry a null cap until their plan is next assigned.
     */
    private void enforceLeadQuota(Long tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        Integer maxLeads = tenant != null ? tenant.getMaxLeads() : null;
        if (maxLeads != null && maxLeads > 0
                && leadRepository.countByTenantIdAndDeletedAtIsNull(tenantId) >= maxLeads) {
            // A dedicated subtype of BusinessException(FORBIDDEN): the human path is unchanged (same
            // 403, same message), but the inbound path can now tell "plan full" apart from every other
            // business failure without catching a supertype and swallowing unrelated bugs. Inbound
            // quarantines instead of 403-ing — see LeadQuotaExceededException.
            throw new LeadQuotaExceededException(
                    "Your plan allows up to " + maxLeads + " leads. "
                            + "Upgrade your plan or remove leads to add more.");
        }
    }

    /**
     * Resolve the assigned user's publicId to a User, scoped to the current
     * tenant so a lead can never be assigned to another tenant's user.
     * Every lead must have an owner, and that owner must be active.
     */
    private User resolveAssignedUser(UUID assignedUserId, Long tenantId) {
        // A sub-agent's leads are ALWAYS assigned to itself: ignore any requested assignee so the lead
        // is visible to it under OWN scope AND it can never hand a lead to another user.
        if (subAgentScope.active()) {
            return userRepository
                    .findByIdAndTenantIdAndDeletedAtIsNull(currentUser().getId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Sub-agent login not found"));
        }
        if (assignedUserId == null) {
            // @NotNull on the DTO catches this first; kept as defense in depth
            throw new BusinessException(
                    "Assigned user is required", HttpStatus.BAD_REQUEST);
        }
        User user = userRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(assignedUserId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Assigned user not found: " + assignedUserId));

        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new BusinessException(
                    "Cannot assign lead to inactive user: " + user.getName(),
                    HttpStatus.BAD_REQUEST);
        }
        return user;
    }

    /**
     * Get logged-in user's email for audit trail (softDelete, etc.)
     */
    private String currentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    /** Current tenant user's internal id, or null (e.g. SuperAdmin) — the notification actor. */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getPrincipal() instanceof User u) ? u.getId() : null;
    }

    /** A lead's owner (assigned user) internal id, or null if somehow unassigned. */
    private Long ownerId(Lead lead) {
        return lead.getAssignedUser() != null ? lead.getAssignedUser().getId() : null;
    }

    /** The authenticated tenant user — used for row-level scope resolution. */
    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User u) {
            return u;
        }
        throw new IllegalStateException("No tenant user in security context");
    }

    /**
     * @param excludePublicId pass null on create, pass lead's publicId on update
     *                        so a lead doesn't conflict with itself
     */
    private void validateNoDuplicates(CreateLeadRequestDto request,
                                      Long tenantId,
                                      UUID excludePublicId) {

        // Only an OPEN lead is a conflict. A CONVERTED/LOST lead for the same contact is
        // kept for history and must NOT block the customer's next inquiry — that block was
        // what made repeat business impossible.
        if (request.getEmail() != null) {
            boolean emailTaken = excludePublicId == null
                    ? leadRepository.existsByEmailAndTenantIdAndDeletedAtIsNullAndLeadStageNotIn(
                    request.getEmail().toLowerCase(), tenantId, TERMINAL_STAGES)
                    : leadRepository.existsByEmailAndTenantIdAndDeletedAtIsNullAndLeadStageNotInAndPublicIdNot(
                    request.getEmail().toLowerCase(), tenantId, TERMINAL_STAGES, excludePublicId);

            if (emailTaken) throw new DuplicateLeadException(
                    "An open lead already exists with email: " + request.getEmail());
        }

        if (request.getPhone() != null) {
            boolean phoneTaken = excludePublicId == null
                    ? leadRepository.existsByPhoneAndTenantIdAndDeletedAtIsNullAndLeadStageNotIn(
                    request.getPhone(), tenantId, TERMINAL_STAGES)
                    : leadRepository.existsByPhoneAndTenantIdAndDeletedAtIsNullAndLeadStageNotInAndPublicIdNot(
                    request.getPhone(), tenantId, TERMINAL_STAGES, excludePublicId);

            if (phoneTaken) throw new DuplicateLeadException(
                    "An open lead already exists with phone: " + request.getPhone());
        }
    }

    /**
     * Create-time restore detection. The submitted email/phone collides with no ACTIVE lead
     * (already verified by {@link #validateNoDuplicates}) but matches a soft-deleted one — so
     * instead of erroring, surface the trashed record's {@code publicId} as a "restore available"
     * 409. Runs only on create. Email is checked first (mirrors the duplicate-check order).
     */
    private void checkTrashedForRestore(CreateLeadRequestDto request, Long tenantId) {
        if (request.getEmail() != null) {
            leadRepository
                    .findFirstByEmailAndTenantIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(
                            request.getEmail().toLowerCase(), tenantId)
                    .ifPresent(trashed -> {
                        throw new RestoreAvailableException(
                                "A lead with email " + request.getEmail()
                                        + " is in Trash. Restore it instead of creating a duplicate.",
                                "LEAD", trashed.getPublicId());
                    });
        }
        if (request.getPhone() != null) {
            leadRepository
                    .findFirstByPhoneAndTenantIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(
                            request.getPhone(), tenantId)
                    .ifPresent(trashed -> {
                        throw new RestoreAvailableException(
                                "A lead with phone " + request.getPhone()
                                        + " is in Trash. Restore it instead of creating a duplicate.",
                                "LEAD", trashed.getPublicId());
                    });
        }
    }

    private void publishLeadCreatedNotification(Lead lead, Long tenantId) {
        // BROADCAST TO EVERYONE WHO COULD OWN THIS LEAD, not just admins and managers.
        //
        // This used to target TENANT_ADMIN + MANAGER only, which made sense when a new lead was
        // purely a supervisory event. It is not any more: the lead is claimable by any eligible
        // agent, and an alert that reaches only the two people who mostly do not work the pipeline
        // is an alert nobody acts on.
        //
        // The audience is AssignableUserResolver's pool — active, unlocked, holds LEAD_READ, never a
        // sub-agent — i.e. exactly the set the auto-assignment could have picked from and the set
        // LeadClaimService will let claim. Deriving it from the same resolver rather than a role
        // list means "who is told" and "who may act" cannot drift apart.
        List<Long> recipientIds = assignableUserResolver.resolve(tenantId, Permission.LEAD_READ)
                .stream()
                .map(User::getId)
                .toList();

        if (recipientIds.isEmpty()) return;

        String assignedTo = lead.getAssignedUser() != null
                ? lead.getAssignedUser().getName()
                : "Unassigned";

        eventPublisher.publishEvent(
                NotifyEvent.builder()
                        .type("LEAD_CREATED")
                        .tenantId(tenantId)
                        .actorUserId(currentUserId())
                        .recipientUserIds(recipientIds)
                        .title("New Lead: " + lead.getCustomerName())
                        .message(lead.getLeadSource() + " lead from " + lead.getDepartCity()
                                + " assigned to " + assignedTo)
                        .referenceType("LEAD")
                        .referencePublicId(lead.getPublicId())
                        .channels(Set.of(DeliveryChannel.IN_APP))
                        .payload(Map.of(
                                "leadId",       lead.getPublicId().toString(),
                                "customerName", lead.getCustomerName(),
                                "source",       lead.getLeadSource(),
                                "assignedTo",   assignedTo
                        ))
                        .build()
        );

        log.info("LEAD_CREATED notification published for lead {} to {} recipients",
                lead.getPublicId(), recipientIds.size());
    }

}
