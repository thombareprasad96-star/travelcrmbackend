package com.crm.travelcrm.leadsource.gateway;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.exception.RestoreAvailableException;
import com.crm.travelcrm.common.util.PhoneCanonicalizer;
import com.crm.travelcrm.lead.attribution.entity.LeadAttribution;
import com.crm.travelcrm.lead.attribution.repository.LeadAttributionRepository;
import com.crm.travelcrm.lead.dto.CreateLeadRequestDto;
import com.crm.travelcrm.lead.dto.LeadResponseDto;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.entity.LeadLog;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.enums.LeadStageGroups;
import com.crm.travelcrm.lead.enums.LeadType;
import com.crm.travelcrm.lead.exception.DuplicateLeadException;
import com.crm.travelcrm.lead.exception.LeadQuotaExceededException;
import com.crm.travelcrm.lead.exception.NoEligibleAssigneeException;
import com.crm.travelcrm.lead.ingest.IngestPolicy;
import com.crm.travelcrm.lead.ingest.LeadActor;
import com.crm.travelcrm.lead.repository.LeadLogRepository;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.lead.service.LeadService;
import com.crm.travelcrm.leadsource.entity.LeadIngestStatus;
import com.crm.travelcrm.leadsource.spi.LeadSourceChannel;
import com.crm.travelcrm.leadsource.spi.NormalizedLead;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Turns a {@link NormalizedLead} into a lead — creating a new one, or appending to an existing open
 * one (owner decision 1).
 *
 * <p><b>Method-level {@code @Transactional}, called CROSS-BEAN from the non-transactional gateway,
 * which has already entered a {@code TenantScope}.</b> Every word of that matters:
 * {@code TenantFilterAspect} is {@code @Before} on {@code @Transactional} and reads
 * {@code TenantContext} at that moment, so the tenant must be set BEFORE this method is entered. It is
 * also why the annotation is on the method and not the class — the aspect's pointcut matches method
 * level only, so a class-level {@code @Transactional} would get no tenant filter at all.
 *
 * <p><b>This class must not publish anything.</b> It RETURNS a {@link LeadIngestOutcome} and the
 * gateway publishes after commit — see that type for why (the reason is not the folklore one).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LeadIngestService {

    private final LeadService leadService;
    private final LeadRepository leadRepository;
    private final LeadLogRepository leadLogRepository;
    private final LeadAttributionRepository attributionRepository;
    private final UserRepository userRepository;

    @Value("${app.lead.default-country-code:+91}")
    private String defaultCountryCode;

    /** Notification type for a machine-created lead. */
    static final String TYPE_LEAD_CREATED = "LEAD_CREATED";

    /** Notification type for a repeat contact appended to an existing lead. */
    static final String TYPE_LEAD_APPENDED = "LEAD_ACTIVITY_APPENDED";

    /**
     * Ingest one normalized lead.
     *
     * @param tenantId  resolved from the ingest token — never from the body
     * @param eventId   the {@code lead_ingest_events} row already committed for this delivery
     */
    @Transactional
    public LeadIngestOutcome ingest(Long tenantId, LeadSourceChannel channel, Long integrationId,
                                    String connectionLabel, Long eventId, NormalizedLead normalized) {
        String canonicalPhone = PhoneCanonicalizer.canonical(normalized.phoneRaw(),
                normalized.phoneCountryHint() != null ? normalized.phoneCountryHint() : defaultCountryCode);

        // Decision 1: a repeat contact from a known OPEN lead is a follow-up on an existing
        // acquisition, not a new lead and not a 409.
        Optional<Lead> existing = findOpenLeadByPhone(tenantId, canonicalPhone);
        if (existing.isPresent()) {
            return append(tenantId, channel, integrationId, connectionLabel, eventId,
                    normalized, existing.get());
        }

        // EVERY declining exception createLead can raise is answered here.
        //
        // The gateway's blanket catch is a backstop for the genuinely unexpected, not a handler: it
        // records FAILED, notifies NOBODY, and still answers the provider 200 — so an enquiry that
        // lands there is gone, and the provider will never retry it. Any decline this method can
        // FORESEE must therefore be turned into a quarantine outcome with a recipient list, right
        // here. See LeadServiceImpl.createLead's noRollbackFor for why catching them is even possible.
        try {
            return create(tenantId, channel, integrationId, connectionLabel, eventId, normalized);

        } catch (LeadQuotaExceededException e) {
            // Decision 9: the enquiry is VISIBLE rather than lost, and the provider still gets a 200.
            log.warn("Inbound lead QUARANTINED (plan cap) | tenant: {} | channel: {} | connection: {}",
                    tenantId, channel.slug(), connectionLabel);
            return quarantine(LeadIngestStatus.QUARANTINED_QUOTA, tenantId, channel, connectionLabel,
                    "Lead blocked: plan limit reached",
                    "An enquiry arrived from " + sourceName(channel) + " (" + connectionLabel + ") "
                            + "but your plan's lead limit is full. It was not created. "
                            + "Upgrade your plan to stop losing enquiries.");

        } catch (DuplicateLeadException e) {
            // The append probe and the duplicate check do not use the same key — see
            // QUARANTINED_DUPLICATE. Nothing is auto-appended here: an email can be a shared family
            // or office address, and silently attaching a stranger's enquiry to someone else's lead
            // is the exact failure findOpenLeadByPhone refuses to risk on an unparseable phone.
            log.warn("Inbound lead QUARANTINED (duplicate of an open lead) | tenant: {} | channel: {} "
                    + "| connection: {}", tenantId, channel.slug(), connectionLabel);
            return quarantine(LeadIngestStatus.QUARANTINED_DUPLICATE, tenantId, channel, connectionLabel,
                    "Enquiry not created: contact already has an open lead",
                    "A new enquiry for " + fallbackName(normalized) + " arrived from "
                            + sourceName(channel) + " (" + connectionLabel + "), but an open lead "
                            + "already exists for this contact, so no second lead was created. "
                            + "Open that lead and log the enquiry against it.");

        } catch (RestoreAvailableException e) {
            log.warn("Inbound lead QUARANTINED (matching lead is in Trash) | tenant: {} | channel: {} "
                    + "| connection: {}", tenantId, channel.slug(), connectionLabel);
            return quarantine(LeadIngestStatus.QUARANTINED_TRASHED, tenantId, channel, connectionLabel,
                    "Enquiry blocked: matching lead is in Trash",
                    "An enquiry for " + fallbackName(normalized) + " arrived from "
                            + sourceName(channel) + " (" + connectionLabel + "), but a lead for this "
                            + "contact is in Trash, so it was not created. Restore that lead — until "
                            + "you do, every further enquiry from this contact is blocked too.");

        } catch (NoEligibleAssigneeException e) {
            log.error("Inbound lead could not be assigned | tenant: {} | channel: {} | connection: {} "
                    + "— the tenant has no eligible user. The lead is recorded FAILED rather than "
                    + "dropped.", tenantId, channel.slug(), connectionLabel);
            throw e;   // the gateway records FAILED; a 200 still goes back to the provider
        }
    }

    /**
     * The append target: the tenant's newest OPEN lead on this canonical phone.
     *
     * <p>A null canonical phone never matches — deliberately. Treating "we could not canonicalise it"
     * as a match would append a stranger's enquiry to whichever unparseable-phone lead happened to be
     * first.
     */
    private Optional<Lead> findOpenLeadByPhone(Long tenantId, String canonicalPhone) {
        if (canonicalPhone == null) {
            return Optional.empty();
        }
        return leadRepository
                .findFirstByPhoneNormalizedAndTenantIdAndDeletedAtIsNullAndLeadStageNotInOrderByCreatedAtDesc(
                        canonicalPhone, tenantId, LeadStageGroups.TERMINAL_STAGES);
    }

    /**
     * Append the repeat contact as an activity log.
     *
     * <p>The existing lead's stage, owner and attribution row are <b>untouched</b>: attribution is
     * first-touch, and silently reopening or reassigning someone's lead because the customer called
     * again is not the framework's decision to make. No new lead row means no contact with the unique
     * indexes either.
     */
    private LeadIngestOutcome append(Long tenantId, LeadSourceChannel channel, Long integrationId,
                                     String connectionLabel, Long eventId,
                                     NormalizedLead normalized, Lead lead) {
        LeadLog logRow = LeadLog.builder()
                .lead(lead)
                .comment(appendComment(channel, connectionLabel, normalized))
                .stageSnapshot(lead.getLeadStage())
                .activityKind("INBOUND_" + channel.name())
                .ingestEventId(eventId)
                .sourceIntegrationId(integrationId)
                .addedByName(connectionLabel)
                .build();
        logRow.setTenantId(tenantId);   // EXPLICIT, never ambient
        leadLogRepository.save(logRow);

        log.info("Inbound lead APPENDED to existing lead | tenant: {} | lead: {} | channel: {}",
                tenantId, lead.getPublicId(), channel.slug());

        // Addressed to the lead's OWNER ONLY — not admins+managers. An append already has an owner,
        // and fanning every repeat call out to every admin is what gets the notification bell muted.
        List<Long> recipients = lead.getAssignedUser() == null
                ? List.of()
                : List.of(lead.getAssignedUser().getId());

        return new LeadIngestOutcome(
                LeadIngestStatus.APPENDED,
                lead.getId(),
                lead.getPublicId(),
                TYPE_LEAD_APPENDED,
                recipients,
                "New enquiry from " + lead.getCustomerName(),
                connectionLabel + " received another enquiry from this contact.");
    }

    private String appendComment(LeadSourceChannel channel, String connectionLabel,
                                 NormalizedLead normalized) {
        StringBuilder sb = new StringBuilder("Repeat enquiry received via ")
                .append(channel.leadSource().getDisplayName())
                .append(" (").append(connectionLabel).append(").");
        if (normalized.message() != null && !normalized.message().isBlank()) {
            sb.append("\n\n").append(normalized.message().trim());
        }
        return sb.toString();
    }

    /**
     * Create a new lead through the SHARED {@code createLead} — never a second create path.
     *
     * <p>Forking would mean quota, duplicate detection, assignment and auditing drift apart from the
     * human path within a release or two. The actor is what makes one implementation serve both.
     */
    private LeadIngestOutcome create(Long tenantId, LeadSourceChannel channel, Long integrationId,
                                     String connectionLabel, Long eventId, NormalizedLead normalized) {

        CreateLeadRequestDto request = new CreateLeadRequestDto();
        request.setCustomerName(fallbackName(normalized));
        request.setPhone(normalized.phoneRaw());
        request.setEmail(normalized.email());        // may be null — an IVR call has no email
        // From the CHANNEL — unless the payload PROVED a more specific origin (a Click-to-WhatsApp ad
        // says "Meta ad", not "WhatsApp"). See NormalizedLead.sourceOverride for why that is one narrow
        // exception and not a hole: the bar is evidence in the payload, never inference.
        request.setLeadSource(normalized.sourceOverride() != null
                ? normalized.sourceOverride()
                : channel.leadSource());
        request.setLeadType(LeadType.FRESH);
        request.setLeadStage(LeadStage.NEW_LEAD);    // the pipeline decides, not sixteen adapters
        request.setNotes(normalized.message());
        applyTravelIntent(request, normalized.travel());
        // assignedUserId is deliberately left null: createLead(MACHINE) routes to assignForInbound and
        // ignores this field entirely. Setting it here would imply an adapter gets to choose an owner.

        LeadResponseDto created = leadService.createLead(
                request,
                LeadActor.integration(integrationId, connectionLabel),
                IngestPolicy.MACHINE);

        Lead lead = leadRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(created.getId(), tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "Lead " + created.getId() + " vanished immediately after creation"));

        writeAttribution(tenantId, lead.getId(), normalized);

        log.info("Inbound lead CREATED | tenant: {} | lead: {} | channel: {} | connection: {}",
                tenantId, lead.getPublicId(), channel.slug(), connectionLabel);

        return new LeadIngestOutcome(
                LeadIngestStatus.PROCESSED,
                lead.getId(),
                lead.getPublicId(),
                // Reuse LEAD_CREATED rather than forking a machine-specific type: a lead created by
                // JustDial is a lead created, with the same triage need, and a second type would force
                // the frontend to learn two names for one concept.
                TYPE_LEAD_CREATED,
                adminAndManagerIds(tenantId),
                "New Lead: " + lead.getCustomerName(),
                "A new lead arrived from " + channel.leadSource().getDisplayName()
                        + " (" + connectionLabel + ").");
    }

    /**
     * Land the provider's travel answers on the lead's real columns — <b>but only where the value is
     * unambiguous.</b>
     *
     * <p>This is the one place in the pipeline that converts a provider's words into typed data, and the
     * governing rule is: <b>an unreadable value is left untyped, never guessed.</b> Everything arrives as
     * the provider wrote it (see {@link NormalizedLead.TravelIntent}) because these are usually preset
     * dropdown answers — {@code "₹50,000 - ₹1,00,000"}, {@code "3+"} — not clean scalars.
     *
     * <p>Nothing is lost when a conversion is declined: the full text is already in
     * {@code request.notes}, so the desk sees exactly what the customer chose. A wrong budget on a lead
     * is worse than an empty one, because an empty one is visibly empty and a wrong one is invisible.
     *
     * <p>The two string fields are copied unconditionally — there is nothing to misread — and they are
     * a real win: {@code departCity}/{@code departCountry} otherwise default to "Not Specified".
     * Destination has no {@code Lead} column at all today; it reaches the desk through the notes.
     */
    private void applyTravelIntent(CreateLeadRequestDto request, NormalizedLead.TravelIntent travel) {
        if (travel == null) {
            return;   // the normal case: no channel but a lead-form asks these questions
        }
        request.setDepartCity(clip(travel.departureCity(), 100));
        request.setDepartCountry(clip(travel.departureCountry(), 100));
        parseIsoDate(travel.departureDate()).ifPresent(request::setTravelDate);
        parsePlainMoney(travel.budget()).ifPresent(request::setBudget);
        parsePlainCount(travel.travellers()).ifPresent(request::setAdults);
    }

    /**
     * ISO {@code yyyy-MM-dd} ONLY — every other shape is declined on purpose.
     *
     * <p><b>A numeric date is not disambiguatable and must never be guessed.</b> {@code 03/04/2026} is
     * 3 April to the customer who typed it and 4 March to a US-format parser; both parse cleanly, one is
     * a month-wrong departure date on a real booking enquiry, and nothing anywhere would flag it. There
     * is no format string that is safe to assume, so we assume none.
     */
    private Optional<java.time.LocalDate> parseIsoDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(java.time.LocalDate.parse(raw.trim()));
        } catch (java.time.format.DateTimeParseException e) {
            log.debug("Travel date '{}' is not ISO — left in the notes rather than guessed.", raw);
            return Optional.empty();
        }
    }

    /**
     * A single plain amount, after stripping currency decoration. A RANGE is declined.
     *
     * <p>{@code "₹50,000"} → {@code 50000}. {@code "₹50,000 - ₹1,00,000"} → declined: picking either end
     * of a range invents a number the customer never gave, and picking the low end quietly under-qualifies
     * every lead in the pipeline.
     */
    private Optional<java.math.BigDecimal> parsePlainMoney(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String cleaned = raw.replaceAll("[\\p{Sc}, ]", "").trim();   // currency symbols, commas, spaces
        if (!cleaned.matches("\\d+(\\.\\d+)?")) {
            log.debug("Travel budget '{}' is not a single plain amount — left in the notes.", raw);
            return Optional.empty();
        }
        try {
            return Optional.of(new java.math.BigDecimal(cleaned));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** A plain whole number. {@code "3+"} / {@code "1-2"} are declined — see {@link #parsePlainMoney}. */
    private Optional<Integer> parsePlainCount(String raw) {
        if (raw == null || !raw.trim().matches("\\d{1,3}")) {
            return Optional.empty();
        }
        int value = Integer.parseInt(raw.trim());
        return value > 0 ? Optional.of(value) : Optional.empty();
    }

    /** The DTO caps these at 100 chars; a provider's answer must not fail validation on length. */
    private String clip(String raw, int max) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /**
     * {@code customerName} is NOT NULL on the entity, but a provider may withhold the name until the
     * lead is claimed. A placeholder is the honest option — the alternative is rejecting a real
     * enquiry over a missing display string.
     */
    private String fallbackName(NormalizedLead normalized) {
        String name = normalized.customerName();
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        String phone = normalized.phoneRaw();
        return (phone != null && !phone.isBlank()) ? "Enquiry from " + phone.trim() : "Unnamed enquiry";
    }

    /** First-touch attribution. Never overwritten — a repeat contact appends, it does not re-attribute. */
    private void writeAttribution(Long tenantId, Long leadId, NormalizedLead normalized) {
        NormalizedLead.LeadAttributionData a = normalized.attribution();
        if (a == null) {
            return;
        }
        LeadAttribution row = LeadAttribution.builder()
                .leadId(leadId)                       // LOGICAL FK — see LeadAttribution's javadoc
                .campaignName(a.campaignName())
                .campaignId(a.campaignId())
                .adId(a.adId())
                .adsetId(a.adSetId())
                .formId(a.formId())
                .gclid(a.gclid())
                .keyword(a.keyword())
                .placement(a.placement())
                .build();
        row.setTenantId(tenantId);   // EXPLICIT, never ambient
        attributionRepository.save(row);
    }

    /**
     * Recipients for a new inbound lead.
     *
     * <p>Set EXPLICITLY, because the notification module's implicit fallback resolves TENANT_ADMIN only
     * and silently drops every MANAGER.
     */
    private List<Long> adminAndManagerIds(Long tenantId) {
        return userRepository
                .findByTenantIdAndRoleInAndIsActiveTrue(tenantId, List.of("TENANT_ADMIN", "MANAGER"))
                .stream()
                .map(User::getId)
                .toList();
    }

    /**
     * A delivery that arrived intact but produced no lead, recorded so it is visible and recoverable.
     *
     * <p><b>The recipient list is the whole point.</b> A quarantine that writes only a row on the
     * integration's delivery-history screen is indistinguishable from a silent drop — nobody opens
     * that screen unless they already suspect something. Admins and managers are told, and the raw
     * payload is on the event, so the enquiry can be worked by hand.
     *
     * <p>No {@code leadId}/{@code leadPublicId}: nothing was created, and pointing the notification's
     * deep link at the lead that BLOCKED this one would read as "here is your new lead".
     */
    private LeadIngestOutcome quarantine(LeadIngestStatus status, Long tenantId,
                                         LeadSourceChannel channel, String connectionLabel,
                                         String title, String message) {
        return new LeadIngestOutcome(
                status,
                null,
                null,
                TYPE_LEAD_CREATED,
                adminAndManagerIds(tenantId),
                title,
                message);
    }

    private String sourceName(LeadSourceChannel channel) {
        return channel.leadSource().getDisplayName();
    }
}
