package com.crm.travelcrm.lead.claim.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.lead.alert.LeadAlertAssembler;
import com.crm.travelcrm.lead.alert.LeadAlertBroadcaster;
import com.crm.travelcrm.lead.alert.dto.LeadAlertDto;
import com.crm.travelcrm.lead.assignment.history.LeadAssignmentEvent;
import com.crm.travelcrm.lead.assignment.history.LeadAssignmentEventRepository;
import com.crm.travelcrm.lead.assignment.history.LeadAssignmentEventType;
import com.crm.travelcrm.lead.assignment.service.AssignableUserResolver;
import com.crm.travelcrm.lead.claim.dto.ClaimLeadRequestDto;
import com.crm.travelcrm.lead.claim.dto.LeadClaimResultDto;
import com.crm.travelcrm.lead.claim.dto.ReassignLeadRequestDto;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.lead.enums.LeadOrigin;
import com.crm.travelcrm.lead.enums.LeadOriginGroups;
import com.crm.travelcrm.lead.enums.LeadStage;
import com.crm.travelcrm.lead.exception.LeadClaimLostException;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.lead.service.LeadAccessGuard;
import com.crm.travelcrm.lead.sla.LeadSlaPolicy;
import com.crm.travelcrm.permission.enums.Permission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The Java half of the claim contract.
 *
 * <p><b>What this can and cannot prove.</b> That two simultaneous claims never both succeed is a
 * property of a single SQL statement's WHERE clause, and no amount of mocking demonstrates it — a
 * mock returns whatever it was told to. What IS testable here, and is where the bugs would actually
 * live, is everything around that statement: that the service passes the version the caller
 * submitted rather than re-reading it, that a zero-row result is correctly diagnosed into the three
 * outcomes the UI treats differently, that the SLA measurement is taken once and never overwritten,
 * and that no ownership change ever lands without a matching history row. The SQL half is asserted
 * by {@code LeadClaimConcurrencyIT} against a real database.
 */
class LeadClaimServiceTest {

    private static final Long TENANT = 42L;

    /**
     * How long ago each fixture lead was created. Applied at fixture-BUILD time, never as a static
     * constant: {@code stampFirstContact} measures against the real {@code now()}, so a createdAt
     * frozen at class load drifts by however long the preceding tests took — which made the measured
     * gap grow through the class and the assertion fail only when run with its siblings.
     */
    private static final int AGE_MINUTES = 2;

    private LeadRepository leadRepository;
    private LeadAccessGuard leadAccessGuard;
    private LeadAssignmentEventRepository eventRepository;
    private AssignableUserResolver assignableUserResolver;
    private UserRepository userRepository;
    private LeadAlertBroadcaster alertBroadcaster;
    private LeadClaimService service;

    private User arjun;      // the actor in most tests
    private User priya;      // the incumbent owner

    @BeforeEach
    void setUp() {
        leadRepository = mock(LeadRepository.class);
        leadAccessGuard = mock(LeadAccessGuard.class);
        eventRepository = mock(LeadAssignmentEventRepository.class);
        assignableUserResolver = mock(AssignableUserResolver.class);
        userRepository = mock(UserRepository.class);

        alertBroadcaster = mock(LeadAlertBroadcaster.class);
        LeadSlaPolicy slaPolicy = new LeadSlaPolicy(300);
        // The assembler is REAL, not a mock: it is pure mapping, and mocking it would hide exactly
        // the bug worth catching here — a broadcast payload built from a stale pre-CAS entity.
        service = new LeadClaimService(leadRepository, leadAccessGuard, eventRepository,
                assignableUserResolver, userRepository, slaPolicy,
                new LeadAlertAssembler(slaPolicy), alertBroadcaster);

        arjun = user(1L, "Arjun Sharma");
        priya = user(2L, "Priya Sharma");

        TenantContext.setTenantId(TENANT);
        authenticateAs(arjun);
        // Default: both are ordinary, eligible lead owners.
        when(assignableUserResolver.resolve(eq(TENANT), eq(Permission.LEAD_READ)))
                .thenReturn(List.of(arjun, priya));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── Claim: the happy path ────────────────────────────────────────────────

    @Test
    @DisplayName("a won claim submits the version the CALLER saw, not one it re-read for itself")
    void claimSubmitsTheCallersVersion() {
        Lead open = openLead(7);
        Lead afterClaim = openLead(8);
        afterClaim.setAssignedUser(arjun);

        when(leadAccessGuard.requireVisibleOrClaimable(any(), any())).thenReturn(open);
        when(leadRepository.claimLead(anyLong(), eq(TENANT), eq(arjun), eq(7),
                eq(LeadOriginGroups.INBOUND_ORIGINS), any())).thenReturn(1);
        when(leadRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(any(), eq(TENANT)))
                .thenReturn(Optional.of(afterClaim));

        LeadClaimResultDto result = service.claim(open.getPublicId(), claimAt(7));

        // Re-reading the current version and submitting THAT would turn the compare-and-swap into a
        // blind overwrite that always wins — the exact bug the version exists to prevent.
        verify(leadRepository).claimLead(anyLong(), eq(TENANT), eq(arjun), eq(7),
                eq(LeadOriginGroups.INBOUND_ORIGINS), any());
        assertThat(result.getOwnerName()).isEqualTo("Arjun Sharma");
        assertThat(result.getClaimVersion()).isEqualTo(8);
        assertThat(result.isOpenToClaim()).isTrue();
    }

    @Test
    @DisplayName("a won claim writes a CLAIMED history row naming both sides of the transfer")
    void claimWritesHistory() {
        Lead open = openLead(3);
        open.setAssignedUser(priya);
        Lead afterClaim = openLead(4);
        afterClaim.setAssignedUser(arjun);

        when(leadAccessGuard.requireVisibleOrClaimable(any(), any())).thenReturn(open);
        when(leadRepository.claimLead(anyLong(), eq(TENANT), any(), anyInt(),
                eq(LeadOriginGroups.INBOUND_ORIGINS), any())).thenReturn(1);
        when(leadRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(any(), eq(TENANT)))
                .thenReturn(Optional.of(afterClaim));

        service.claim(open.getPublicId(), claimAt(3));

        ArgumentCaptor<LeadAssignmentEvent> captor =
                ArgumentCaptor.forClass(LeadAssignmentEvent.class);
        verify(eventRepository).save(captor.capture());
        LeadAssignmentEvent saved = captor.getValue();

        assertThat(saved.getEventType()).isEqualTo(LeadAssignmentEventType.CLAIMED);
        assertThat(saved.getFromUserName()).isEqualTo("Priya Sharma");
        assertThat(saved.getToUserName()).isEqualTo("Arjun Sharma");
        assertThat(saved.getActorUserId()).isEqualTo(1L);
        assertThat(saved.getTenantId()).isEqualTo(TENANT);
    }

    // ── Broadcast: what the OTHER users' screens depend on ───────────────────

    @Test
    @DisplayName("a won claim broadcasts the POST-claim state, not the entity it read first")
    void claimBroadcastsTheFreshState() {
        Lead open = openLead(3);
        open.setAssignedUser(priya);
        Lead afterClaim = openLead(4);
        afterClaim.setAssignedUser(arjun);

        when(leadAccessGuard.requireVisibleOrClaimable(any(), any())).thenReturn(open);
        when(leadRepository.claimLead(anyLong(), eq(TENANT), any(), anyInt(),
                eq(LeadOriginGroups.INBOUND_ORIGINS), any())).thenReturn(1);
        when(leadRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(any(), eq(TENANT)))
                .thenReturn(Optional.of(afterClaim));

        service.claim(open.getPublicId(), claimAt(3));

        ArgumentCaptor<LeadAlertDto> captor = ArgumentCaptor.forClass(LeadAlertDto.class);
        verify(alertBroadcaster).broadcastClaimed(eq(TENANT), captor.capture());
        LeadAlertDto alert = captor.getValue();

        // Broadcasting the pre-CAS entity is the subtle bug this guards: every other tab would
        // receive the OLD owner and the OLD version, so their Claim buttons would re-arm with a
        // version that can never match and the lead would look permanently unclaimable.
        assertThat(alert.getOwnerName()).isEqualTo("Arjun Sharma");
        assertThat(alert.getClaimVersion()).isEqualTo(4);
        assertThat(alert.getPreviousOwnerName()).isEqualTo("Priya Sharma");
        assertThat(alert.isOpenToClaim()).isTrue();
    }

    @Test
    @DisplayName("marking contacted broadcasts a LOCK — this is what removes the row from other screens")
    void contactBroadcastsTheLock() {
        Lead open = openLead(2);
        Lead locked = openLead(3);
        locked.setAssignedUser(priya);
        locked.setFirstContactedAt(LocalDateTime.now());
        locked.setFirstContactedByUserId(1L);
        locked.setLeadStage(LeadStage.CONTACTED);

        when(leadAccessGuard.requireVisibleOrClaimable(any(), any())).thenReturn(open);
        when(leadRepository.markContacted(anyLong(), any(), any(), any(), anyLong(), any()))
                .thenReturn(1);
        when(leadRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(any(), eq(TENANT)))
                .thenReturn(Optional.of(locked));
        when(userRepository.findByIdAndTenantIdAndDeletedAtIsNull(eq(1L), eq(TENANT)))
                .thenReturn(Optional.of(arjun));

        service.markContacted(open.getPublicId(), null);

        ArgumentCaptor<LeadAlertDto> captor = ArgumentCaptor.forClass(LeadAlertDto.class);
        verify(alertBroadcaster).broadcastLockChanged(eq(TENANT), captor.capture());
        // Without openToClaim=false the row stays on every colleague's screen and they discover it
        // is gone only by being refused.
        assertThat(captor.getValue().isOpenToClaim()).isFalse();
        assertThat(captor.getValue().getSlaSecondsRemaining())
                .as("the clock has stopped — null, not a stale countdown that keeps ticking")
                .isNull();
    }

    @Test
    @DisplayName("a LOST claim broadcasts nothing — nothing changed")
    void lostClaimBroadcastsNothing() {
        Lead open = openLead(3);
        Lead nowOwnedByPriya = openLead(4);
        nowOwnedByPriya.setAssignedUser(priya);

        when(leadAccessGuard.requireVisibleOrClaimable(any(), any())).thenReturn(open);
        when(leadRepository.claimLead(anyLong(), eq(TENANT), any(), eq(3),
                eq(LeadOriginGroups.INBOUND_ORIGINS), any())).thenReturn(0);
        when(leadRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(any(), eq(TENANT)))
                .thenReturn(Optional.of(nowOwnedByPriya));

        assertThatThrownBy(() -> service.claim(open.getPublicId(), claimAt(3)))
                .isInstanceOf(LeadClaimLostException.class);

        // The winner already broadcast. A second push from the loser would make every screen
        // animate an ownership change that did not happen.
        verifyNoInteractions(alertBroadcaster);
    }

    @Test
    @DisplayName("reopening puts the lead back in the pool as a NEW lead alert")
    void reopenBroadcastsAsNewLead() {
        Lead locked = lockedLead(9);
        Lead reopened = openLead(10);

        when(leadAccessGuard.requireVisible(any(), any())).thenReturn(locked);
        when(leadRepository.reopenClaimWindow(anyLong(), eq(TENANT), eq(LeadStage.NEW_LEAD), any()))
                .thenReturn(1);
        when(leadRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(any(), eq(TENANT)))
                .thenReturn(Optional.of(reopened));

        service.reopenClaimWindow(locked.getPublicId(), "mis-click");

        ArgumentCaptor<LeadAlertDto> captor = ArgumentCaptor.forClass(LeadAlertDto.class);
        verify(alertBroadcaster).broadcastNewLead(eq(TENANT), captor.capture());
        assertThat(captor.getValue().isOpenToClaim()).isTrue();
        verify(alertBroadcaster, never()).broadcastLockChanged(any(), any());
    }

    // ── Claim: the three ways to lose ────────────────────────────────────────

    @Test
    @DisplayName("losing on a stale version names the winner so the UI can say who took it")
    void staleVersionNamesTheWinner() {
        Lead open = openLead(3);
        Lead nowOwnedByPriya = openLead(4);
        nowOwnedByPriya.setAssignedUser(priya);

        when(leadAccessGuard.requireVisibleOrClaimable(any(), any())).thenReturn(open);
        when(leadRepository.claimLead(anyLong(), eq(TENANT), any(), eq(3),
                eq(LeadOriginGroups.INBOUND_ORIGINS), any())).thenReturn(0);
        when(leadRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(any(), eq(TENANT)))
                .thenReturn(Optional.of(nowOwnedByPriya));

        assertThatThrownBy(() -> service.claim(open.getPublicId(), claimAt(3)))
                .isInstanceOf(LeadClaimLostException.class)
                .hasMessageContaining("Priya Sharma")
                .extracting("reason", "currentClaimVersion")
                .containsExactly(LeadClaimLostException.Reason.VERSION_STALE, 4);

        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("losing to a contact-stamp is reported as ALREADY_CONTACTED, not a stale version")
    void contactedLeadIsDistinguishedFromAStaleVersion() {
        Lead open = openLead(3);
        Lead locked = openLead(4);
        locked.setAssignedUser(priya);
        locked.setFirstContactedAt(LocalDateTime.now());
        locked.setLeadStage(LeadStage.CONTACTED);

        when(leadAccessGuard.requireVisibleOrClaimable(any(), any())).thenReturn(open);
        when(leadRepository.claimLead(anyLong(), eq(TENANT), any(), anyInt(),
                eq(LeadOriginGroups.INBOUND_ORIGINS), any())).thenReturn(0);
        when(leadRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(any(), eq(TENANT)))
                .thenReturn(Optional.of(locked));

        // The distinction is not cosmetic: a stale version is retryable and the UI re-arms the
        // button, a locked lead is not and the button must disappear.
        assertThatThrownBy(() -> service.claim(open.getPublicId(), claimAt(3)))
                .isInstanceOf(LeadClaimLostException.class)
                .extracting("reason")
                .isEqualTo(LeadClaimLostException.Reason.ALREADY_CONTACTED);
    }

    @Test
    @DisplayName("a lead that went Lost while the alert was on screen reports TERMINAL_STAGE")
    void terminalLeadIsDistinguished() {
        Lead open = openLead(3);
        Lead lost = openLead(3);
        lost.setLeadStage(LeadStage.LOST);

        when(leadAccessGuard.requireVisibleOrClaimable(any(), any())).thenReturn(open);
        when(leadRepository.claimLead(anyLong(), eq(TENANT), any(), anyInt(),
                eq(LeadOriginGroups.INBOUND_ORIGINS), any())).thenReturn(0);
        when(leadRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(any(), eq(TENANT)))
                .thenReturn(Optional.of(lost));

        assertThatThrownBy(() -> service.claim(open.getPublicId(), claimAt(3)))
                .isInstanceOf(LeadClaimLostException.class)
                .extracting("reason")
                .isEqualTo(LeadClaimLostException.Reason.TERMINAL_STAGE);
    }

    // ── Claim: guards ────────────────────────────────────────────────────────

    @Test
    @DisplayName("claiming a lead you already own is a silent no-op, not a version bump")
    void selfClaimDoesNothing() {
        Lead mine = openLead(5);
        mine.setAssignedUser(arjun);
        when(leadAccessGuard.requireVisibleOrClaimable(any(), any())).thenReturn(mine);

        LeadClaimResultDto result = service.claim(mine.getPublicId(), claimAt(5));

        // Bumping the version here would invalidate every OTHER client's button for no change of
        // state — two of your own tabs would fight each other.
        verify(leadRepository, never()).claimLead(anyLong(), any(), any(), anyInt(), any(), any());
        verifyNoInteractions(eventRepository);
        assertThat(result.getOwnerName()).isEqualTo("Arjun Sharma");
        assertThat(result.getClaimVersion()).isEqualTo(5);
    }

    @Test
    @DisplayName("a manually created lead cannot enter the tenant-wide claim race")
    void manualLeadCannotBeClaimed() {
        Lead manual = openLead(1);
        manual.setOrigin(LeadOrigin.MANUAL);
        manual.setAssignedUser(priya);
        when(leadAccessGuard.requireVisibleOrClaimable(any(), any())).thenReturn(manual);

        assertThatThrownBy(() -> service.claim(manual.getPublicId(), claimAt(1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only inbound leads can be claimed")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(leadRepository, never()).claimLead(anyLong(), any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("holding LEAD_CLAIM is not enough — the claimer must be an eligible lead OWNER")
    void ineligibleUserCannotClaimEvenWithThePermission() {
        Lead open = openLead(1);
        when(leadAccessGuard.requireVisibleOrClaimable(any(), any())).thenReturn(open);
        // A sub-agent, or a user the assignment engine would never pick: present in the tenant,
        // absent from the assignable pool.
        when(assignableUserResolver.resolve(eq(TENANT), eq(Permission.LEAD_READ)))
                .thenReturn(List.of(priya));

        assertThatThrownBy(() -> service.claim(open.getPublicId(), claimAt(1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be assigned leads");

        verify(leadRepository, never()).claimLead(anyLong(), any(), any(), anyInt(), any(), any());
    }

    // ── Contact: the lock and the SLA ────────────────────────────────────────

    @Test
    @DisplayName("first contact measures create → now and stamps the actor, not the owner")
    void firstContactMeasuresAndRecordsTheActor() {
        Lead open = openLead(2);
        open.setAssignedUser(priya);                 // owned by Priya...
        Lead locked = openLead(3);
        locked.setAssignedUser(priya);               // ...and STILL owned by Priya afterwards
        locked.setFirstContactedAt(LocalDateTime.now());
        locked.setFirstContactedByUserId(1L);        // ...but Arjun made the call
        locked.setFirstResponseSeconds(120L);
        locked.setLeadStage(LeadStage.CONTACTED);

        when(leadAccessGuard.requireVisibleOrClaimable(any(), any())).thenReturn(open);
        when(leadRepository.markContacted(anyLong(), eq(TENANT), any(), eq(1L), anyLong(),
                eq(LeadStage.CONTACTED))).thenReturn(1);
        when(leadRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(any(), eq(TENANT)))
                .thenReturn(Optional.of(locked));
        when(userRepository.findByIdAndTenantIdAndDeletedAtIsNull(eq(1L), eq(TENANT)))
                .thenReturn(Optional.of(arjun));

        LeadClaimResultDto result = service.markContacted(open.getPublicId(), null);

        ArgumentCaptor<Long> seconds = ArgumentCaptor.forClass(Long.class);
        verify(leadRepository).markContacted(anyLong(), eq(TENANT), any(), eq(1L),
                seconds.capture(), eq(LeadStage.CONTACTED));
        Long measured = seconds.getValue();
        assertThat(measured)
                .as("create -> contact was %d minutes; measured %ss", AGE_MINUTES, measured)
                .isBetween(AGE_MINUTES * 60L, AGE_MINUTES * 60L + 5);

        assertThat(result.getOwnerName())
                .as("marking contacted must NOT silently transfer ownership")
                .isEqualTo("Priya Sharma");
        assertThat(result.getFirstContactedByName()).isEqualTo("Arjun Sharma");
        assertThat(result.isOpenToClaim()).isFalse();
    }

    @Test
    @DisplayName("re-contact after a reopen PRESERVES the original measurement")
    void reContactAfterReopenPreservesTheSla() {
        Lead reopened = openLead(6);
        reopened.setFirstResponseSeconds(900L);   // measured before the manager reopened it

        when(leadAccessGuard.requireVisibleOrClaimable(any(), any())).thenReturn(reopened);
        when(leadRepository.markContactedPreservingSla(anyLong(), eq(TENANT), any(), eq(1L),
                eq(LeadStage.CONTACTED))).thenReturn(1);
        when(leadRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(any(), eq(TENANT)))
                .thenReturn(Optional.of(reopened));

        service.markContacted(reopened.getPublicId(), null);

        // Otherwise reopen-then-recontact is a one-click way to erase a missed SLA, which makes the
        // whole breach metric unfalsifiable.
        verify(leadRepository).markContactedPreservingSla(anyLong(), eq(TENANT), any(), eq(1L),
                eq(LeadStage.CONTACTED));
        verify(leadRepository, never())
                .markContacted(anyLong(), any(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("clock skew cannot produce a negative first-response time")
    void clockSkewIsClamped() {
        Lead future = openLead(0);
        future.setCreatedAt(LocalDateTime.now().plusMinutes(5));   // another node's clock is ahead

        when(leadAccessGuard.requireVisibleOrClaimable(any(), any())).thenReturn(future);
        when(leadRepository.markContacted(anyLong(), any(), any(), any(), anyLong(), any()))
                .thenReturn(1);
        when(leadRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(any(), eq(TENANT)))
                .thenReturn(Optional.of(future));

        service.markContacted(future.getPublicId(), null);

        ArgumentCaptor<Long> seconds = ArgumentCaptor.forClass(Long.class);
        verify(leadRepository).markContacted(anyLong(), any(), any(), any(),
                seconds.capture(), any());
        // A negative value would sort as the fastest response of the month and quietly win awards.
        assertThat(seconds.getValue()).isZero();
    }

    @Test
    @DisplayName("the second of two concurrent contact-stamps loses rather than re-measuring")
    void secondContactStampLoses() {
        Lead open = openLead(2);
        Lead locked = openLead(3);
        locked.setFirstContactedAt(LocalDateTime.now());
        locked.setLeadStage(LeadStage.CONTACTED);
        locked.setAssignedUser(priya);

        when(leadAccessGuard.requireVisibleOrClaimable(any(), any())).thenReturn(open);
        when(leadRepository.markContacted(anyLong(), any(), any(), any(), anyLong(), any()))
                .thenReturn(0);
        when(leadRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(any(), eq(TENANT)))
                .thenReturn(Optional.of(locked));

        assertThatThrownBy(() -> service.markContacted(open.getPublicId(), null))
                .isInstanceOf(LeadClaimLostException.class)
                .extracting("reason")
                .isEqualTo(LeadClaimLostException.Reason.ALREADY_CONTACTED);

        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("a stage that is not engagement cannot be used to stamp first contact")
    void nonEngagedStageCannotStampContact() {
        Lead open = openLead(1);

        assertThatThrownBy(() -> service.stampFirstContact(open, LeadStage.LOST, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not represent first contact");

        verify(leadRepository, never())
                .markContacted(anyLong(), any(), any(), any(), anyLong(), any());
    }

    // ── Post-lock supervision ────────────────────────────────────────────────

    @Test
    @DisplayName("an OPEN lead cannot be reassigned — it must go through claim, so it cannot skip the CAS")
    void openLeadCannotBeReassigned() {
        Lead open = openLead(1);
        open.setAssignedUser(priya);
        when(leadAccessGuard.requireVisible(any(), any())).thenReturn(open);

        assertThatThrownBy(() -> service.reassign(open.getPublicId(), reassignTo(arjun)))
                .isInstanceOf(LeadClaimLostException.class)
                .extracting("reason")
                .isEqualTo(LeadClaimLostException.Reason.NOT_LOCKED);

        verify(leadRepository, never()).reassignLockedLead(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("a locked lead reassigns and records both sides")
    void lockedLeadReassigns() {
        Lead locked = lockedLead(4);
        locked.setAssignedUser(priya);
        Lead after = lockedLead(5);
        after.setAssignedUser(arjun);

        when(leadAccessGuard.requireVisible(any(), any())).thenReturn(locked);
        when(userRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(eq(arjun.getPublicId()),
                eq(TENANT))).thenReturn(Optional.of(arjun));
        when(leadRepository.reassignLockedLead(anyLong(), eq(TENANT), eq(arjun), any()))
                .thenReturn(1);
        when(leadRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(any(), eq(TENANT)))
                .thenReturn(Optional.of(after));

        service.reassign(locked.getPublicId(), reassignTo(arjun));

        ArgumentCaptor<LeadAssignmentEvent> captor =
                ArgumentCaptor.forClass(LeadAssignmentEvent.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType())
                .isEqualTo(LeadAssignmentEventType.REASSIGNED);
        assertThat(captor.getValue().getFromUserName()).isEqualTo("Priya Sharma");
        assertThat(captor.getValue().getToUserName()).isEqualTo("Arjun Sharma");
    }

    @Test
    @DisplayName("reopen is refused once the lead has moved past Contacted")
    void reopenRefusedAfterTheLeadProgressed() {
        Lead working = lockedLead(9);
        working.setLeadStage(LeadStage.PROPOSAL_SENT);
        when(leadAccessGuard.requireVisible(any(), any())).thenReturn(working);

        // Throwing a lead with a live proposal back into the claim pool would discard real pipeline
        // state. Reopen exists for one thing: undoing a mis-click seconds after it happened.
        assertThatThrownBy(() -> service.reopenClaimWindow(working.getPublicId(), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Proposal Sent")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(leadRepository, never()).reopenClaimWindow(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("reopen from Contacted succeeds and is recorded")
    void reopenFromContactedSucceeds() {
        Lead locked = lockedLead(9);
        Lead reopened = openLead(10);
        reopened.setFirstResponseSeconds(600L);   // measurement survives

        when(leadAccessGuard.requireVisible(any(), any())).thenReturn(locked);
        when(leadRepository.reopenClaimWindow(anyLong(), eq(TENANT), eq(LeadStage.NEW_LEAD), any()))
                .thenReturn(1);
        when(leadRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(any(), eq(TENANT)))
                .thenReturn(Optional.of(reopened));

        LeadClaimResultDto result = service.reopenClaimWindow(locked.getPublicId(), "mis-click");

        assertThat(result.isOpenToClaim()).isTrue();
        assertThat(result.getFirstResponseSeconds())
                .as("the original measurement is not erased by reopening")
                .isEqualTo(600L);

        ArgumentCaptor<LeadAssignmentEvent> captor =
                ArgumentCaptor.forClass(LeadAssignmentEvent.class);
        verify(eventRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(LeadAssignmentEventType.REOPENED);
        assertThat(captor.getValue().getNote()).isEqualTo("mis-click");
    }

    @Test
    @DisplayName("a manually created lead has no claim window to reopen")
    void manualLeadCannotBeReopened() {
        Lead manual = lockedLead(9);
        manual.setOrigin(LeadOrigin.MANUAL);
        when(leadAccessGuard.requireVisible(any(), any())).thenReturn(manual);

        assertThatThrownBy(() -> service.reopenClaimWindow(manual.getPublicId(), "mis-click"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only inbound leads have a claim window")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(leadRepository, never()).reopenClaimWindow(anyLong(), any(), any(), any());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static ClaimLeadRequestDto claimAt(int version) {
        ClaimLeadRequestDto dto = new ClaimLeadRequestDto();
        dto.setExpectedClaimVersion(version);
        return dto;
    }

    private static ReassignLeadRequestDto reassignTo(User user) {
        ReassignLeadRequestDto dto = new ReassignLeadRequestDto();
        dto.setAssignedUserId(user.getPublicId());
        return dto;
    }

    private Lead openLead(int claimVersion) {
        Lead lead = new Lead();
        lead.setId(500L);
        lead.setPublicId(UUID.randomUUID());
        lead.setTenantId(TENANT);
        lead.setLeadCode("LD-26-0042");
        lead.setCustomerName("Rohit Verma");
        lead.setCreatedAt(LocalDateTime.now().minusMinutes(AGE_MINUTES));
        lead.setOrigin(LeadOrigin.INTEGRATION);
        lead.setLeadStage(LeadStage.NEW_LEAD);
        lead.setClaimVersion(claimVersion);
        lead.setSlaTargetSeconds(300);
        return lead;
    }

    private Lead lockedLead(int claimVersion) {
        Lead lead = openLead(claimVersion);
        lead.setLeadStage(LeadStage.CONTACTED);
        lead.setFirstContactedAt(lead.getCreatedAt().plusMinutes(1));
        lead.setFirstContactedByUserId(2L);
        lead.setFirstResponseSeconds(60L);
        return lead;
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

    private static void authenticateAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
