package com.crm.travelcrm.lead.assignment.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.enums.Role;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.lead.assignment.dto.AssignmentRecommendationResponse;
import com.crm.travelcrm.lead.assignment.dto.EligibleUserDto;
import com.crm.travelcrm.lead.assignment.entity.LeadAssignmentPointer;
import com.crm.travelcrm.lead.assignment.repository.LeadAssignmentPointerRepository;
import com.crm.travelcrm.lead.assignment.strategy.AssignmentContext;
import com.crm.travelcrm.lead.assignment.strategy.AssignmentStrategyType;
import com.crm.travelcrm.lead.assignment.strategy.LeadAssignmentStrategyResolver;
import com.crm.travelcrm.permission.enums.Permission;
import com.crm.travelcrm.permission.service.ScopeResolver;
import com.crm.travelcrm.workload.WorkloadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates intelligent lead assignment. It is the ONLY place lead creation talks to for choosing
 * an owner, so the strategy set can grow without touching {@code LeadServiceImpl}. Two entry points:
 *
 * <ol>
 *   <li>{@link #recommendForCreate()} — a read-only <b>peek</b> that powers the create-form pre-fill
 *       (badge + dropdown for admins/managers, forced-self for everyone else). It never assigns and
 *       never advances the round-robin cursor.</li>
 *   <li>{@link #assignForCreate(UUID)} — the <b>authoritative</b> decision made inside the
 *       lead-creation transaction: it honours an admin/manager's explicit choice, recomputes the
 *       recommendation for the audit trail, and advances the persisted round-robin cursor exactly
 *       once (under a pessimistic per-tenant lock so concurrent creations can't race it).</li>
 * </ol>
 *
 * <p><b>Role split.</b> Only {@code TENANT_ADMIN} and {@code MANAGER} get the load-based
 * recommendation with an editable dropdown; every other role (agents, staff, sub-agents) is
 * force-assigned to itself — generalising, and preserving, the pre-existing sub-agent self-assign rule.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LeadAssignmentService {

    private final UserRepository userRepository;
    private final AssignableUserResolver assignableUserResolver;
    private final LeadAssignmentPointerRepository pointerRepository;
    private final LeadAssignmentPointerProvisioner pointerProvisioner;
    private final LeadAssignmentStrategyResolver strategyResolver;
    private final ScopeResolver scopeResolver;
    private final WorkloadService workloadService;

    /** Eligibility gate for the assignable pool: a user must be able to at least VIEW leads. */
    private static final Permission ELIGIBILITY_PERMISSION = Permission.LEAD_READ;
    private static final String LOAD_BASED_LABEL = "Load-Based Assignment";
    private static final String SELF_LABEL = "Self Assignment";

    // ── Peek: powers the create-form "Assign To" control (read-only, no side effects) ──

    @Transactional(readOnly = true)
    public AssignmentRecommendationResponse recommendForCreate() {
        Long tenantId = currentTenantId();
        User current = currentUser();

        // Agents / staff / sub-agents → the UI hides the dropdown and assigns the lead to self.
        if (!isPrivilegedAssigner(current)) {
            EligibleUserDto self = new EligibleUserDto(
                    current.getPublicId(), current.getName(), current.getEmail(), 0L);
            return AssignmentRecommendationResponse.builder()
                    .strategy(AssignmentStrategyType.SELF.name())
                    .strategyLabel(SELF_LABEL)
                    .forcedSelf(true)
                    .self(self)
                    .recommendedUserId(current.getPublicId())
                    .recommendedUserName(current.getName())
                    .eligibleUsers(List.of())
                    .build();
        }

        // Tenant admin / manager → load-based recommendation (peek only). The DROPDOWN pool includes
        // everyone eligible (so an admin can still MANUALLY assign a lead to itself), but Tenant
        // Admins are excluded from the auto-recommendation CANDIDATES — an admin supervises rather
        // than works the pipeline, so it is never auto-picked.
        List<User> pool = resolveScopedPool(tenantId, current);
        List<Long> poolIds = sortedIds(pool);
        Map<Long, Long> counts = workloadScores(tenantId, poolIds);
        List<Long> candidateIds = recommendationCandidates(pool);
        Long cursor = pointerRepository.findFirstByTenantId(tenantId)
                .map(LeadAssignmentPointer::getLastRecommendedUserId)
                .orElse(null);

        Long recommendedId = strategyResolver
                .resolve(AssignmentStrategyType.LOAD_BASED)
                .recommend(context(tenantId, current.getId(), null, candidateIds, counts, cursor))
                .orElse(null);
        User recommended = findInPool(pool, recommendedId);

        List<EligibleUserDto> eligible = pool.stream()
                .map(u -> new EligibleUserDto(u.getPublicId(), u.getName(), u.getEmail(),
                        counts.getOrDefault(u.getId(), 0L)))
                .toList();

        return AssignmentRecommendationResponse.builder()
                .strategy(AssignmentStrategyType.LOAD_BASED.name())
                .strategyLabel(LOAD_BASED_LABEL)
                .forcedSelf(false)
                .self(new EligibleUserDto(current.getPublicId(), current.getName(), current.getEmail(),
                        counts.getOrDefault(current.getId(), 0L)))
                .recommendedUserId(recommended == null ? null : recommended.getPublicId())
                .recommendedUserName(recommended == null ? null : recommended.getName())
                .eligibleUsers(eligible)
                .build();
    }

    // ── Commit: authoritative decision made inside the lead-creation transaction ──

    /**
     * Decide the new lead's owner. Runs inside the caller's ({@code LeadServiceImpl.createLead})
     * transaction so the per-tenant pessimistic lock on the round-robin cursor is held through commit.
     *
     * @param requestedAssigneePublicId the assignee the client submitted (publicId); for a privileged
     *                                  creator this is honoured (an override), for everyone else it is
     *                                  ignored in favour of self.
     */
    @Transactional
    public AssignmentOutcome assignForCreate(UUID requestedAssigneePublicId) {
        Long tenantId = currentTenantId();
        User current = currentUser();

        // Non-privileged → forced self-assignment (preserves & generalises the sub-agent rule),
        // routed through the SELF strategy so the Strategy Pattern is genuinely exercised.
        if (!isPrivilegedAssigner(current)) {
            Long selfId = strategyResolver.resolve(AssignmentStrategyType.SELF)
                    .recommend(context(tenantId, current.getId(), null, List.of(), Map.of(), null))
                    .orElse(current.getId());
            User self = resolveSelf(tenantId, selfId);
            return new AssignmentOutcome(self, self.getId(), self.getName(),
                    AssignmentStrategyType.SELF, false);
        }

        // Privileged → recompute the recommendation (authoritative, for the audit) and advance the
        // cursor, then honour the explicit choice. Tenant Admins are excluded from the recommendation
        // candidates (but not from being an honoured explicit choice).
        List<User> pool = resolveScopedPool(tenantId, current);
        List<Long> candidateIds = recommendationCandidates(pool);
        Map<Long, Long> counts = workloadScores(tenantId, candidateIds);

        // Ensure the cursor row EXISTS in its own short transaction BEFORE we take the pessimistic
        // lock, so a losing first-ever INSERT aborts only that inner txn — never this lead-creation
        // transaction (a same-txn catch-and-reread would poison it on Postgres).
        pointerProvisioner.ensureExists(tenantId);

        // Serialize concurrent creations for THIS tenant on the cursor row (SELECT ... FOR UPDATE),
        // so the read-then-advance below can never lose an update. The row is guaranteed to exist now.
        LeadAssignmentPointer pointer = pointerRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "Lead assignment pointer missing after provisioning for tenant " + tenantId));

        Long recommendedId = strategyResolver
                .resolve(AssignmentStrategyType.LOAD_BASED)
                .recommend(context(tenantId, current.getId(), null,
                        candidateIds, counts, pointer.getLastRecommendedUserId()))
                .orElse(null);

        // Advance the persisted cursor to the pick (once per privileged create) so the NEXT
        // recommendation rotates to the next tied user; survives a server restart.
        if (recommendedId != null) {
            pointer.setLastRecommendedUserId(recommendedId);
            pointerRepository.saveAndFlush(pointer);
        }
        String recommendedName = nameOf(pool, recommendedId);

        // Final assignee = the admin/manager's explicit choice (honoured), else the recommendation.
        // A submitted choice must still be a member of the eligible pool, so a crafted request can't
        // assign a lead to a sub-agent or a user without LEAD_READ (the FE only offers pool members).
        User finalUser;
        if (requestedAssigneePublicId != null) {
            finalUser = resolveSubmittedAssignee(requestedAssigneePublicId, tenantId);
            assertInPool(pool, finalUser);
        } else {
            finalUser = resolveRecommended(recommendedId, tenantId);
        }

        boolean override = recommendedId == null || !recommendedId.equals(finalUser.getId());
        AssignmentStrategyType strategy =
                override ? AssignmentStrategyType.MANUAL : AssignmentStrategyType.LOAD_BASED;

        return new AssignmentOutcome(finalUser, recommendedId, recommendedName, strategy, override);
    }

    // ── Commit: inbound (machine) path — no security context, no requester ──

    /**
     * Decide an INBOUND lead's owner: lowest workload, round-robin among ties.
     *
     * <p><b>Why this cannot reuse {@link #assignForCreate}.</b> That method opens with
     * {@code currentUser()}, which throws when no {@code User} is in the security context — and on the
     * ingest path there is none: the caller is a webhook authenticated by an opaque token, not a
     * person. Every downstream branch there (privileged check, self-assignment, honouring an explicit
     * choice) is likewise meaningless for a machine. This is the same strategy and the same cursor,
     * with the human-only decisions removed rather than faked with a synthetic user.
     *
     * <p>{@code tenantId} is passed in rather than read from {@code TenantContext}: the ingest gateway
     * resolves the tenant from the ingest token, and depending on a ThreadLocal here would make the
     * assignment silently follow whatever context happened to be on the thread.
     *
     * <p>Runs in the caller's transaction (the lead-creation one), so the pessimistic lock on the
     * cursor is held to commit exactly as on the human path.
     *
     * @return the chosen owner, or {@link Optional#empty()} when the tenant has NOBODY eligible.
     *         Empty is a real, reachable state — a tenant can deactivate every agent — and it is
     *         returned rather than thrown because the caller must quarantine the lead (HTTP 200 +
     *         notify) instead of failing the webhook. A 4xx/5xx to JustDial means retries and
     *         eventually a disabled integration; the lead would be lost to protect an invariant that
     *         quarantine already protects.
     */
    @Transactional
    public Optional<AssignmentOutcome> assignForInbound(Long tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("assignForInbound requires an explicit tenantId");
        }

        // The FULL eligible pool: there is no requesting user, so there is no row-scope to narrow by.
        // resolveScopedPool exists to stop a TEAM-scoped manager seeing other teams' names in a
        // dropdown — a privacy concern with no meaning here.
        List<User> pool = assignableUserResolver.resolve(tenantId, ELIGIBILITY_PERMISSION);
        List<Long> candidateIds = recommendationCandidates(pool);
        if (candidateIds.isEmpty()) {
            log.warn("Inbound lead assignment found NO eligible user for tenant {} — caller must "
                    + "quarantine the lead rather than drop it.", tenantId);
            return Optional.empty();
        }

        Map<Long, Long> scores = workloadScores(tenantId, candidateIds);

        pointerProvisioner.ensureExists(tenantId);
        LeadAssignmentPointer pointer = pointerRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "Lead assignment pointer missing after provisioning for tenant " + tenantId));

        Long recommendedId = strategyResolver
                .resolve(AssignmentStrategyType.LOAD_BASED)
                .recommend(context(tenantId, null, null,
                        candidateIds, scores, pointer.getLastRecommendedUserId()))
                .orElse(null);

        if (recommendedId == null) {
            // Candidates existed but the strategy declined — defensive; treated as "nobody available".
            log.warn("Inbound lead assignment: LOAD_BASED returned no pick for tenant {} despite {} "
                    + "candidate(s).", tenantId, candidateIds.size());
            return Optional.empty();
        }

        pointer.setLastRecommendedUserId(recommendedId);
        pointerRepository.saveAndFlush(pointer);

        User assignee = userRepository.findByIdAndTenantIdAndDeletedAtIsNull(recommendedId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Recommended user not found for inbound assignment"));

        // manualOverride=false: no human chose this. The audit reads "LOAD_BASED, not overridden",
        // which is exactly what happened.
        return Optional.of(new AssignmentOutcome(assignee, recommendedId, assignee.getName(),
                AssignmentStrategyType.LOAD_BASED, false));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private boolean isPrivilegedAssigner(User u) {
        return u.getRole() == Role.TENANT_ADMIN || u.getRole() == Role.MANAGER;
    }

    private AssignmentContext context(Long tenantId, Long currentUserId, Long requestedAssigneeUserId,
                                      List<Long> candidateIds, Map<Long, Long> counts, Long cursor) {
        return AssignmentContext.builder()
                .tenantId(tenantId)
                .currentUserId(currentUserId)
                .requestedAssigneeUserId(requestedAssigneeUserId)
                .candidateUserIds(candidateIds)
                .workloadScores(counts)
                .lastAssignedUserId(cursor)
                .build();
    }

    /** Candidate ids sorted ascending — deterministic input for round-robin rotation. */
    private List<Long> sortedIds(List<User> pool) {
        return pool.stream().map(User::getId).sorted().toList();
    }

    /**
     * The auto-recommendation candidates: the eligible pool minus Tenant Admins. Admins stay in the
     * dropdown (so an admin can MANUALLY assign a lead to itself) but are never auto-recommended,
     * since an admin supervises rather than works the pipeline. Sorted ascending for round-robin.
     */
    private List<Long> recommendationCandidates(List<User> pool) {
        return pool.stream()
                .filter(u -> u.getRole() != Role.TENANT_ADMIN)
                .map(User::getId)
                .sorted()
                .toList();
    }

    /**
     * Workload score per candidate, zero-filled so an idle user is present with 0.
     *
     * <p>Delegates to {@link WorkloadService} rather than counting leads here: the calendar's
     * workload tab reads the same service, so what a manager sees and what the auto-assignment
     * balances on cannot drift. This used to count active leads only.
     */
    private Map<Long, Long> workloadScores(Long tenantId, List<Long> candidateIds) {
        if (candidateIds.isEmpty()) {
            return Map.of();
        }
        return workloadService.scoresFor(tenantId, candidateIds);
    }

    private User findInPool(List<User> pool, Long userId) {
        if (userId == null) {
            return null;
        }
        return pool.stream().filter(u -> userId.equals(u.getId())).findFirst().orElse(null);
    }

    private String nameOf(List<User> pool, Long userId) {
        User u = findInPool(pool, userId);
        return u == null ? null : u.getName();
    }

    private User resolveSelf(Long tenantId, Long userId) {
        return userRepository.findByIdAndTenantIdAndDeletedAtIsNull(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Current user not found in tenant"));
    }

    /** Validate an explicitly requested assignee (tenant-scoped, must be active) — mirrors the
     *  existing create/update assignment validation. */
    private User resolveSubmittedAssignee(UUID publicId, Long tenantId) {
        User user = userRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Assigned user not found: " + publicId));
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new BusinessException(
                    "Cannot assign lead to inactive user: " + user.getName(), HttpStatus.BAD_REQUEST);
        }
        return user;
    }

    /** Fallback when no assignee was submitted (defence in depth — the DTO enforces @NotNull). */
    private User resolveRecommended(Long recommendedId, Long tenantId) {
        if (recommendedId == null) {
            throw new BusinessException(
                    "No eligible user is available to assign this lead.", HttpStatus.BAD_REQUEST);
        }
        return userRepository.findByIdAndTenantIdAndDeletedAtIsNull(recommendedId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Recommended user not found"));
    }

    /**
     * The eligible dropdown pool, additionally narrowed to the users the caller can actually see under
     * its LEAD_READ row-scope (mirrors the workload dashboard). A TENANT_ADMIN / ALL-scoped caller gets
     * the whole tenant; a TEAM-scoped MANAGER gets its team — so the recommendation never leaks the
     * names / emails / active-lead counts of users outside the caller's scope.
     */
    private List<User> resolveScopedPool(Long tenantId, User current) {
        List<User> pool = assignableUserResolver.resolve(tenantId, ELIGIBILITY_PERMISSION);
        Set<Long> visible = scopeResolver.visibleUserIds(current, ELIGIBILITY_PERMISSION.name());
        if (visible == null) {          // ALL scope — no owner restriction
            return pool;
        }
        return pool.stream().filter(u -> visible.contains(u.getId())).toList();
    }

    /** Reject an explicitly-submitted assignee that is not in the eligible pool (e.g. a sub-agent, or
     *  a user without LEAD_READ) — the backend enforces the same membership the dropdown offers. */
    private void assertInPool(List<User> pool, User user) {
        boolean inPool = pool.stream().anyMatch(u -> u.getId() == user.getId());
        if (!inPool) {
            throw new BusinessException(
                    "This user cannot be assigned leads: " + user.getName(), HttpStatus.BAD_REQUEST);
        }
    }

    private Long currentTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty for lead assignment.");
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