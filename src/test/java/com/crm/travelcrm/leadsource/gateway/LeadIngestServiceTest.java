package com.crm.travelcrm.leadsource.gateway;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.exception.RestoreAvailableException;
import com.crm.travelcrm.lead.attribution.repository.LeadAttributionRepository;
import com.crm.travelcrm.lead.dto.CreateLeadRequestDto;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.exception.DuplicateLeadException;
import com.crm.travelcrm.lead.exception.LeadQuotaExceededException;
import com.crm.travelcrm.lead.exception.NoEligibleAssigneeException;
import com.crm.travelcrm.lead.ingest.IngestPolicy;
import com.crm.travelcrm.lead.ingest.LeadActor;
import com.crm.travelcrm.lead.repository.LeadLogRepository;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.lead.service.LeadService;
import com.crm.travelcrm.lead.service.LeadServiceImpl;
import com.crm.travelcrm.leadsource.entity.LeadIngestStatus;
import com.crm.travelcrm.leadsource.spi.LeadSourceChannel;
import com.crm.travelcrm.leadsource.spi.NormalizedLead;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The ingest decline contract: <b>every enquiry the pipeline refuses to turn into a lead must leave
 * somebody a message.</b>
 *
 * <p>This is the half of the pipeline that had no tests at all, and the gap was not cosmetic. The
 * gateway wraps the whole delivery in {@code catch (Exception)} → record FAILED → answer the provider
 * <b>200</b>. A 200 is a promise that we have the enquiry, so the provider never retries. Anything
 * that reaches that catch is therefore gone for good, seen by nobody except whoever opens the
 * integration's delivery-history screen — which nobody does unless they already suspect a problem.
 *
 * <p>So the property under test is not "the right enum comes back". It is: for each way
 * {@code createLead} can decline, the service produces a quarantine outcome <b>with recipients</b>,
 * rather than letting the exception escape into that catch. {@link #quarantineNeverWritesAnything()}
 * and {@link #createLeadDoesNotPoisonTheIngestTransaction()} cover the two ways this fix could be
 * silently undone later.
 */
class LeadIngestServiceTest {

    private static final Long TENANT = 42L;
    private static final Long INTEGRATION_ID = 7L;
    private static final Long EVENT_ID = 900L;
    private static final String LABEL = "Our website";
    private static final LeadSourceChannel CHANNEL = LeadSourceChannel.WEBSITE_FORM;

    private LeadService leadService;
    private LeadRepository leadRepository;
    private LeadLogRepository leadLogRepository;
    private LeadAttributionRepository attributionRepository;
    private UserRepository userRepository;
    private LeadIngestService service;

    @BeforeEach
    void setUp() {
        leadService = mock(LeadService.class);
        leadRepository = mock(LeadRepository.class);
        leadLogRepository = mock(LeadLogRepository.class);
        attributionRepository = mock(LeadAttributionRepository.class);
        userRepository = mock(UserRepository.class);

        service = new LeadIngestService(leadService, leadRepository, leadLogRepository,
                attributionRepository, userRepository);
        // @Value field — null here would change how PhoneCanonicalizer reads an unprefixed number,
        // so it is set to the production default rather than left to chance.
        ReflectionTestUtils.setField(service, "defaultCountryCode", "+91");

        // Default: no open lead on this phone, so every test takes the CREATE path.
        when(leadRepository
                .findFirstByPhoneNormalizedAndTenantIdAndDeletedAtIsNullAndLeadStageNotInOrderByCreatedAtDesc(
                        anyString(), anyLong(), any()))
                .thenReturn(Optional.empty());

        // The people a quarantine has to reach.
        when(userRepository.findByTenantIdAndRoleInAndIsActiveTrue(eq(TENANT), any()))
                .thenReturn(List.of(user(11L, "Admin"), user(12L, "Manager")));
    }

    // ── The four ways createLead declines ────────────────────────────────────

    @Test
    @DisplayName("plan cap: quarantined as QUARANTINED_QUOTA and the tenant is told to upgrade")
    void quotaIsQuarantined() {
        createLeadThrows(new LeadQuotaExceededException("Lead limit reached"));

        LeadIngestOutcome outcome = ingest(lead("Ramesh Kumar", "9876543210", "ramesh@example.com"));

        assertThat(outcome.status()).isEqualTo(LeadIngestStatus.QUARANTINED_QUOTA);
        assertThat(outcome.shouldNotify()).isTrue();
        assertThat(outcome.message()).contains("Upgrade your plan");
    }

    @Test
    @DisplayName("open duplicate: quarantined, NOT dropped — this used to reach the gateway's catch")
    void duplicateIsQuarantined() {
        createLeadThrows(new DuplicateLeadException(
                "An open lead already exists with email: ramesh@example.com"));

        LeadIngestOutcome outcome = ingest(lead("Ramesh Kumar", "9876543210", "ramesh@example.com"));

        assertThat(outcome.status()).isEqualTo(LeadIngestStatus.QUARANTINED_DUPLICATE);
        assertThat(outcome.shouldNotify()).isTrue();
        // The desk cannot act on "a duplicate happened" — it has to know WHOSE enquiry to go and find.
        assertThat(outcome.message()).contains("Ramesh Kumar");
    }

    @Test
    @DisplayName("trashed match: quarantined, and the message says the next one is blocked too")
    void trashedIsQuarantined() {
        createLeadThrows(new RestoreAvailableException(
                "A lead with phone 9876543210 is in Trash.", "LEAD", UUID.randomUUID()));

        LeadIngestOutcome outcome = ingest(lead("Ramesh Kumar", "9876543210", null));

        assertThat(outcome.status()).isEqualTo(LeadIngestStatus.QUARANTINED_TRASHED);
        assertThat(outcome.shouldNotify()).isTrue();
        assertThat(outcome.message()).contains("Trash");
    }

    @Test
    @DisplayName("no eligible assignee: still rethrown — a lead with no possible owner is not a "
            + "quarantine, it is a broken tenant")
    void noEligibleAssigneeIsRethrown() {
        createLeadThrows(new NoEligibleAssigneeException(TENANT));

        assertThatThrownBy(() -> ingest(lead("Ramesh Kumar", "9876543210", null)))
                .isInstanceOf(NoEligibleAssigneeException.class);
    }

    // ── The two ways this fix could be silently undone ───────────────────────

    @Test
    @DisplayName("a quarantine writes no lead, no log and no attribution row")
    void quarantineNeverWritesAnything() {
        createLeadThrows(new DuplicateLeadException("An open lead already exists"));

        LeadIngestOutcome outcome = ingest(lead("Ramesh Kumar", "9876543210", "ramesh@example.com"));

        // No leadId/leadPublicId: nothing was created, and pointing the notification at the lead that
        // BLOCKED this one would read as "here is your new lead".
        assertThat(outcome.leadId()).isNull();
        assertThat(outcome.leadPublicId()).isNull();
        verifyNoInteractions(attributionRepository, leadLogRepository);
    }

    @Test
    @DisplayName("createLead must not mark the ingest transaction rollback-only for a pre-write decline")
    void createLeadDoesNotPoisonTheIngestTransaction() throws NoSuchMethodException {
        // Catching an exception cannot undo a rollback-only flag. LeadIngestService calls createLead
        // cross-bean from INSIDE its own transaction, so a rollback-worthy exception there marks the
        // SHARED transaction — the quarantine outcome then returns normally and the outer commit
        // throws UnexpectedRollbackException, landing the delivery in the gateway's catch with no
        // notification. That is precisely the silent loss the quarantines above exist to stop, and it
        // would come back the moment someone "tidies up" this annotation. Hence an assertion on it.
        Method createLead = LeadServiceImpl.class.getMethod(
                "createLead", CreateLeadRequestDto.class, LeadActor.class, IngestPolicy.class);

        Class<? extends Throwable>[] noRollback =
                createLead.getAnnotation(Transactional.class).noRollbackFor();

        assertThat(noRollback).contains(
                LeadQuotaExceededException.class,
                DuplicateLeadException.class,
                RestoreAvailableException.class);
        // NoEligibleAssigneeException is raised AFTER the gapless lead-code counter has been consumed,
        // so its transaction genuinely must roll back — and the ingest path rethrows it anyway.
        assertThat(noRollback).doesNotContain(NoEligibleAssigneeException.class);
    }

    // ── The path a quarantine must never steal ───────────────────────────────

    @Test
    @DisplayName("a repeat contact on an open lead still APPENDS — createLead is never reached")
    void repeatContactStillAppends() {
        Lead existing = new Lead();
        existing.setId(55L);
        existing.setPublicId(UUID.randomUUID());
        existing.setTenantId(TENANT);
        existing.setCustomerName("Ramesh Kumar");
        existing.setLeadStage(LeadStage.FOLLOW_UP);
        when(leadRepository
                .findFirstByPhoneNormalizedAndTenantIdAndDeletedAtIsNullAndLeadStageNotInOrderByCreatedAtDesc(
                        anyString(), anyLong(), any()))
                .thenReturn(Optional.of(existing));

        LeadIngestOutcome outcome = ingest(lead("Ramesh Kumar", "9876543210", "ramesh@example.com"));

        assertThat(outcome.status()).isEqualTo(LeadIngestStatus.APPENDED);
        assertThat(outcome.leadId()).isEqualTo(55L);
        verifyNoInteractions(leadService);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private LeadIngestOutcome ingest(NormalizedLead normalized) {
        return service.ingest(TENANT, CHANNEL, INTEGRATION_ID, LABEL, EVENT_ID, normalized);
    }

    private void createLeadThrows(RuntimeException e) {
        when(leadService.createLead(any(CreateLeadRequestDto.class), any(LeadActor.class),
                eq(IngestPolicy.MACHINE)))
                .thenThrow(e);
    }

    private static NormalizedLead lead(String name, String phone, String email) {
        return NormalizedLead.builder()
                .customerName(name)
                .phoneRaw(phone)
                .email(email)
                .message("Goa package chahiye")
                .build();
    }

    private static User user(long id, String name) {
        User user = new User();
        user.setId(id);
        user.setPublicId(UUID.randomUUID());
        user.setTenantId(TENANT);
        user.setName(name);
        user.setIsActive(true);
        return user;
    }
}
