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
import com.crm.travelcrm.lead.enums.LeadStageGroups;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.permission.enums.Permission;
import com.crm.travelcrm.permission.service.ScopeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final LeadRepository leadRepository;
    private final AssignableUserResolver assignableUserResolver;
    private final LeadAssignmentPointerRepository pointerRepository;
    private final LeadAssignmentPointerProvisioner pointerProvisioner;
    private final LeadAssignmentStrategyResolver strategyResolver;
    private final ScopeResolver scopeResolver;

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
        Map<Long, Long> counts = activeLeadCounts(tenantId, poolIds);
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
        Map<Long, Long> counts = activeLeadCounts(tenantId, candidateIds);

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
                .activeLeadCounts(counts)
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

    /** Active-lead count per candidate, zero-filled so a user with no active leads is present with 0. */
    private Map<Long, Long> activeLeadCounts(Long tenantId, List<Long> candidateIds) {
        Map<Long, Long> counts = new HashMap<>();
        for (Long id : candidateIds) {
            counts.put(id, 0L);
        }
        if (candidateIds.isEmpty()) {
            return counts;
        }
        for (Object[] row : leadRepository.countActiveLeadsPerUser(
                tenantId, candidateIds, LeadStageGroups.ACTIVE_STAGES)) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
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