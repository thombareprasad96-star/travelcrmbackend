package com.crm.travelcrm.booking.assignment;

import com.crm.travelcrm.auth.api.CurrentUserProvider;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.lead.assignment.service.AssignableUserResolver;
import com.crm.travelcrm.lead.entity.Lead;
import com.crm.travelcrm.permission.enums.Permission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The rules that decide who services a booking.
 *
 * <p>The two that actually carry risk, and why they are worth a test each:
 * <ul>
 *   <li><b>Convert defaults to the LEAD's assignee, not the converting user.</b> The tempting
 *       implementation — "assign it to whoever pressed the button" — is wrong in exactly the case
 *       that matters: a manager converting on an agent's behalf would silently take the account.</li>
 *   <li><b>An explicit choice equal to the default is always allowed.</b> The UI prefills the
 *       dropdown with the default and posts it back, so a naive pool-only check rejects the
 *       untouched happy path whenever the default sits outside the pool (a sub-agent's lead).
 *       That regression would look like "conversion randomly 400s for some leads".</li>
 * </ul>
 */
class BookingAssigneeResolverTest {

    private static final Long TENANT = 7L;

    private AssignableUserResolver pool;
    private UserRepository userRepository;
    private CurrentUserProvider currentUser;
    private BookingAssigneeResolver resolver;

    private User agent;      // id 10 — in the pool
    private User manager;    // id 20 — in the pool
    private User subAgent;   // id 30 — NOT in the pool (the pool excludes sub-agents)

    @BeforeEach
    void setUp() {
        pool = mock(AssignableUserResolver.class);
        userRepository = mock(UserRepository.class);
        currentUser = mock(CurrentUserProvider.class);
        resolver = new BookingAssigneeResolver(pool, userRepository, currentUser);

        agent    = user(10L, "Agent A");
        manager  = user(20L, "Manager M");
        subAgent = user(30L, "Sub Agent S");

        when(pool.resolve(eq(TENANT), any(Permission.class))).thenReturn(List.of(agent, manager));
        stubLookup(agent);
        stubLookup(manager);
        stubLookup(subAgent);
    }

    private static User user(long id, String name) {
        User u = User.builder().name(name).email(name.replace(' ', '.') + "@t.com").build();
        u.setId(id);
        u.setPublicId(UUID.randomUUID());
        return u;
    }

    /** Every user resolves by publicId within TENANT, and by nothing outside it. */
    private void stubLookup(User u) {
        when(userRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(u.getPublicId(), TENANT))
                .thenReturn(Optional.of(u));
    }

    private static Lead leadAssignedTo(User u) {
        Lead lead = new Lead();
        lead.setAssignedUser(u);
        return lead;
    }

    @Nested
    @DisplayName("conversion default")
    class ConvertDefault {

        @Test
        void keepsTheLeadsAssignee_notTheUserDoingTheConverting() {
            // A manager is converting an agent's lead. The customer must stay with the agent.
            when(currentUser.currentUserIdOrNull()).thenReturn(manager.getId());

            Long assignee = resolver.resolveForConvert(leadAssignedTo(agent), null, TENANT);

            assertThat(assignee).isEqualTo(agent.getId());
        }

        @Test
        void isNullWhenTheLeadItselfHasNoAssignee() {
            // Legacy leads predate the NOT NULL assignee rule — carry the gap rather than invent
            // an owner the deal never had.
            assertThat(resolver.resolveForConvert(leadAssignedTo(null), null, TENANT)).isNull();
        }
    }

    @Nested
    @DisplayName("direct-create default")
    class CreateDefault {

        @Test
        void isTheCurrentUser() {
            when(currentUser.currentUserIdOrNull()).thenReturn(manager.getId());
            assertThat(resolver.resolveForCreate(null, TENANT)).isEqualTo(manager.getId());
        }

        @Test
        void isNullWhenThereIsNoTenantUserInContext() {
            when(currentUser.currentUserIdOrNull()).thenReturn(null);
            assertThat(resolver.resolveForCreate(null, TENANT)).isNull();
        }
    }

    @Nested
    @DisplayName("explicit override")
    class Override {

        @Test
        void isHonouredWhenTheChoiceIsInThePool() {
            Long assignee = resolver.resolveForConvert(
                    leadAssignedTo(agent), manager.getPublicId(), TENANT);
            assertThat(assignee).isEqualTo(manager.getId());
        }

        @Test
        void isRejectedWhenTheChoiceIsOutsideThePool() {
            assertThatThrownBy(() -> resolver.resolveForConvert(
                    leadAssignedTo(agent), subAgent.getPublicId(), TENANT))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("cannot be assigned bookings");
        }

        @Test
        void isA404WhenThePublicIdBelongsToAnotherTenant() {
            // Not stubbed for TENANT → the tenant-scoped finder returns empty. A foreign publicId
            // must 404, never resolve.
            UUID foreign = UUID.randomUUID();
            assertThatThrownBy(() -> resolver.resolveForConvert(leadAssignedTo(agent), foreign, TENANT))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void acceptsThePrefilledDefaultEvenThoughItIsOutsideThePool() {
            // THE regression guard. The lead belongs to a sub-agent; the UI prefills the dropdown
            // with them and posts it straight back. That is our own server-derived value being
            // echoed — a pool-only rule would 400 the untouched happy path.
            Long assignee = resolver.resolveForConvert(
                    leadAssignedTo(subAgent), subAgent.getPublicId(), TENANT);

            assertThat(assignee).isEqualTo(subAgent.getId());
        }

        @Test
        void acceptsSelfOnDirectCreateEvenForANonPoolUser() {
            // Same rule, other path: a sub-agent keying in its own booking echoes back the default.
            when(currentUser.currentUserIdOrNull()).thenReturn(subAgent.getId());

            assertThat(resolver.resolveForCreate(subAgent.getPublicId(), TENANT))
                    .isEqualTo(subAgent.getId());
        }
    }
}
