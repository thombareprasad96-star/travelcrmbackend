package com.crm.travelcrm.task.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.permission.service.ScopeResolver;
import com.crm.travelcrm.permission.service.SubAgentScope;
import com.crm.travelcrm.task.entity.Task;
import com.crm.travelcrm.task.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * The single entry point for reading a task under tenant isolation AND the caller's row-level scope.
 * Modelled directly on {@code CommAccessGuard} / {@code LeadAccessGuard}.
 *
 * <p><b>Why this exists.</b> Before the All Tasks screen, the scope rule lived inline in three
 * separate places inside {@code TaskServiceImpl} — the by-id fetch, the {@code list} specification,
 * and a post-fetch Java filter in {@code getMyTasks}. Every new read path (the paged list, the five
 * tab counts) would have added another copy, and copies drift. They all resolve here now.
 *
 * <p>Always throws 404, never 403, for a missing / cross-tenant / out-of-scope task. A 403 confirms
 * the row exists, which for a task means confirming which customer an agency is working on.
 *
 * <h2>Two scopes apply, at two DIFFERENT layers — read this before widening either</h2>
 * <ol>
 *   <li><b>{@code SubAgentScope}</b> — narrow, and only ever active for {@code SUB_AGENT}: confines
 *       a B2B partner to tasks it <i>owns</i> ({@code owner_user_id = self}). Applied on
 *       <b>every</b> path, including by-id.</li>
 *   <li><b>{@code ScopeResolver}</b> — the OWN/TEAM/ALL axis on the <i>assignee</i>, with a hard
 *       {@code TENANT_ADMIN → ALL} bypass. Applied on the <b>All Tasks list only</b>, via
 *       {@link #visibleAssigneeIds}.</li>
 * </ol>
 *
 * <h2>Why by-id is deliberately NOT scoped by ScopeResolver</h2>
 * Before this screen, {@code /api/tasks/{publicId}} applied only {@code SubAgentScope} — so every
 * non-sub-agent role could open any task in the tenant. Routing it through {@code ScopeResolver}
 * would tighten that silently: its role defaults are {@code OWN} for TRAVEL_AGENT/STAFF and
 * {@code TEAM} for MANAGER, so an agent would suddenly 404 on tasks they can open today.
 *
 * <p>Crucially, the existing board and calendar still list tenant-wide (they use
 * {@code SubAgentScope} only), so tightening by-id alone would produce the one genuinely broken
 * combination: <b>rows that are listed but 404 when clicked</b>. A list narrower than by-id access
 * is harmless; a list wider than it is not.
 *
 * <p>So the new All Tasks list is the narrow one and by-id keeps today's behaviour. Tightening
 * by-id is a real product decision — it should be made deliberately, together with the board and
 * calendar, not as a side effect of adding a screen.
 *
 * <p><b>An UNASSIGNED task stays visible</b> to anyone whose scope is not NONE. {@code canSee()}
 * rejects a null owner outright, so leaning on it would hide every unassigned task from every
 * non-admin — and unassigned work that nobody can see is work nobody ever claims. Same reasoning,
 * same resolution, as {@code CommAccessGuard}.
 */
@Component
@RequiredArgsConstructor
public class TaskAccessGuard {

    private final TaskRepository taskRepository;
    private final ScopeResolver scopeResolver;
    private final SubAgentScope subAgentScope;

    /**
     * Resolve a task by publicId within the tenant and the caller's row scope, or 404.
     *
     * <p>Applies {@code SubAgentScope} only — see the class javadoc for why the OWN/TEAM/ALL axis
     * is deliberately not applied here.
     */
    public Task requireVisible(UUID publicId, String permissionKey) {
        Task task = taskRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, requireTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + publicId));
        assertVisible(task, permissionKey);
        return task;
    }

    /** Scope-check an already-fetched task (sub-agent axis; see the class javadoc). */
    public void assertVisible(Task task, String permissionKey) {
        subAgentScope.assertVisible(task, task.getPublicId());
    }

    /**
     * The stricter by-id check the module does NOT use yet: sub-agent axis PLUS the OWN/TEAM/ALL
     * assignee scope.
     *
     * <p>Kept here, unused by production paths, so that when the owner decides to narrow by-id
     * access it is a one-line switch in {@code TaskServiceImpl.findOrThrow} rather than a rewrite —
     * and so the intended rule is written down instead of living in a design doc. Switching to it
     * requires narrowing the board/calendar list at the same time, or the list will show rows that
     * 404 when opened.
     */
    public void assertVisibleStrict(Task task, String permissionKey) {
        subAgentScope.assertVisible(task, task.getPublicId());

        Long assignee = task.getAssignToUserId();
        if (assignee == null) {
            // Unclaimed work — visible to anyone whose scope is not NONE. See class javadoc.
            if (scopeResolver.resolveScope(currentUser(), permissionKey) == ScopeResolver.Scope.NONE) {
                throw new ResourceNotFoundException("Task not found: " + task.getPublicId());
            }
            return;
        }
        if (!scopeResolver.canSee(currentUser(), permissionKey, assignee)) {
            throw new ResourceNotFoundException("Task not found: " + task.getPublicId());
        }
    }

    /**
     * The assignee ids the caller may see, or {@code null} for no restriction (scope ALL).
     *
     * <p>An empty set means NONE — the caller must return an empty page, not an unfiltered one.
     */
    public Set<Long> visibleAssigneeIds(String permissionKey) {
        return scopeResolver.visibleUserIds(currentUser(), permissionKey);
    }

    /** The owner id a sub-agent is confined to, or {@code null} for every other role. */
    public Long ownerFilter() {
        return subAgentScope.ownerFilter();
    }

    public Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "TenantContext is empty. Ensure JwtAuthFilter is running and the JWT carries a tenantId.");
        }
        return tenantId;
    }

    public User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User u) {
            return u;
        }
        throw new IllegalStateException("Tasks are available to tenant users only");
    }

    public Long currentUserId() {
        return currentUser().getId();
    }
}
