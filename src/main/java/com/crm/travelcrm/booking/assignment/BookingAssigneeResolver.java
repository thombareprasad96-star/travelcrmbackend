package com.crm.travelcrm.booking.assignment;

import com.crm.travelcrm.auth.api.CurrentUserProvider;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.lead.assignment.service.AssignableUserResolver;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.permission.enums.Permission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Decides which staff member a booking is assigned to, for both creation paths.
 *
 * <h3>The defaults</h3>
 * <ul>
 *   <li><b>Lead → Booking conversion:</b> the LEAD's assignee. The person who nurtured the deal
 *       keeps the customer — <i>not</i> whoever happened to click Convert (a manager converting on
 *       an agent's behalf must not silently steal the account). Relationship-based, deliberately
 *       NOT load-based: unlike an inbound lead, a booking already has a human attached to it.</li>
 *   <li><b>Direct create:</b> the current user. There is no lead to inherit from, and whoever is
 *       keying in a booking is by default the one servicing it.</li>
 * </ul>
 * Both defaults are only defaults — the caller sends {@code assignedUserId} to override.
 *
 * <h3>What an explicit choice is allowed to be</h3>
 * The submitted user must be in the eligible pool ({@link #ELIGIBILITY_PERMISSION}, same rule
 * {@code AssignableUserResolver} applies for leads) <b>or</b> be exactly the default. That second
 * arm is load-bearing, not a loophole: the UI prefills the dropdown with the default and submits it
 * back, so a pool-only rule would reject the untouched happy path whenever the default sits outside
 * the pool — a lead assigned to a sub-agent (the pool excludes sub-agents), or a sub-agent keying in
 * its own booking. Both are legitimate; neither widens what a crafted request can reach, because the
 * default is server-derived.
 */
@Component
@RequiredArgsConstructor
public class BookingAssigneeResolver {

    /** Eligibility gate for the assignable pool: a user must at least be able to VIEW bookings. */
    static final Permission ELIGIBILITY_PERMISSION = Permission.BOOKING_READ;

    private final AssignableUserResolver assignableUserResolver;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;

    /**
     * Assignee for a lead conversion: the explicit choice if sent, else the lead's own assignee.
     *
     * @return the internal user id to stamp on the booking, or null when the lead itself has no
     *         assignee and nothing was chosen (legacy leads predate the NOT NULL assignee rule).
     */
    @Transactional(readOnly = true)
    public Long resolveForConvert(Lead lead, UUID requestedPublicId, Long tenantId) {
        Long defaultId = lead.getAssignedUser() != null ? lead.getAssignedUser().getId() : null;
        return resolve(requestedPublicId, defaultId, tenantId);
    }

    /**
     * Assignee for a directly-created booking: the explicit choice if sent, else the current user.
     *
     * @return the internal user id to stamp, or null when there is no tenant user in context
     *         (a machine-driven create) and nothing was chosen.
     */
    @Transactional(readOnly = true)
    public Long resolveForCreate(UUID requestedPublicId, Long tenantId) {
        return resolve(requestedPublicId, currentUserProvider.currentUserIdOrNull(), tenantId);
    }

    /**
     * Assignee for an existing booking: keep the current assignee when omitted, otherwise validate
     * the requested public UUID against the same tenant-scoped eligible pool used at creation.
     */
    @Transactional(readOnly = true)
    public Long resolveForUpdate(UUID requestedPublicId, Long currentAssigneeId, Long tenantId) {
        return resolve(requestedPublicId, currentAssigneeId, tenantId);
    }

    /** The eligible pool for the "Assign To" dropdown, name-ordered. */
    @Transactional(readOnly = true)
    public List<User> eligibleUsers(Long tenantId) {
        return assignableUserResolver.resolve(tenantId, ELIGIBILITY_PERMISSION);
    }

    // ── internals ────────────────────────────────────────────────────────────

    private Long resolve(UUID requestedPublicId, Long defaultId, Long tenantId) {
        if (requestedPublicId == null) {
            return defaultId;
        }

        // Tenant-scoped resolution: a publicId from another tenant is a 404, never a silent
        // cross-tenant assignment.
        User requested = userRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(requestedPublicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found: " + requestedPublicId));

        if (Objects.equals(requested.getId(), defaultId)) {
            return defaultId;   // the untouched prefill — always allowed, it is our own value
        }
        assertInPool(requested, tenantId);
        return requested.getId();
    }

    private void assertInPool(User requested, Long tenantId) {
        boolean eligible = assignableUserResolver.resolve(tenantId, ELIGIBILITY_PERMISSION).stream()
                .anyMatch(u -> u.getId() == requested.getId());
        if (!eligible) {
            throw new BusinessException(
                    requested.getName() + " cannot be assigned bookings. Choose someone from the list.",
                    HttpStatus.BAD_REQUEST);
        }
    }
}
