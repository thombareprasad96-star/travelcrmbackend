package com.crm.travelcrm.permission;

import com.crm.travelcrm.auth.enums.Role;
import com.crm.travelcrm.permission.enums.Permission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Communication Center's permission rules, which otherwise live only in prose — a javadoc
 * on {@link Permission} and a SQL comment in {@code V2__lead_code.sql} PART 17.
 *
 * <p><b>Why this test exists.</b> A permission has TWO independent grant paths in this codebase:
 * {@link Permission#defaultsFor(Role)}, used for a user whose permission screen has never been
 * saved, and the migration backfill, used for a user whose has. {@code EffectivePermissionResolver}
 * treats a non-null saved map as authoritative and does NOT merge role defaults into it, so the two
 * paths must agree or a user's access depends on whether anyone ever clicked Save on their profile.
 * {@code FleetMoneyPermissionDefaultsTest} was written after exactly that divergence shipped; this
 * is the same guard for this module.
 *
 * <p>The consequence here is less dramatic than an Rs 40,000 payout, but it is the more likely bug:
 * two agents in one office, one of whom silently cannot see the shared inbox.
 */
class CommPermissionDefaultsTest {

    /**
     * Keys deliberately granted to NO role by default.
     *
     * <p>Reading a colleague's private notes and listening to a recorded customer call are privacy
     * decisions an owner makes per person, not capabilities that arrive with a job title. Template
     * and workflow management are configuration. All four follow the {@code BOOKING_REFUND} /
     * {@code LEAD_PERMANENT_DELETE} precedent: TENANT_ADMIN holds them via the resolver's bypass,
     * everyone else is granted them explicitly.
     */
    private static final Set<Permission> GRANTED_PER_USER_ONLY = EnumSet.of(
            Permission.COMM_NOTE_PRIVATE_READ,
            Permission.COMM_RECORDING_READ,
            Permission.COMM_TEMPLATE_MANAGE,
            Permission.COMM_WORKFLOW_MANAGE);

    /** Roles the PART 17 backfill targets: {@code u.role IN ('MANAGER','TRAVEL_AGENT','ACCOUNTANT','SUB_AGENT')}. */
    private static final Set<Role> BACKFILLED_ROLES = EnumSet.of(
            Role.MANAGER, Role.TRAVEL_AGENT, Role.ACCOUNTANT, Role.SUB_AGENT);

    @ParameterizedTest
    @EnumSource(Role.class)
    @DisplayName("private notes, recordings, templates and workflows are never granted by role")
    void sensitiveKeysAreNeverInherited(Role role) {
        Set<Permission> defaults = Permission.defaultsFor(role);

        if (role == Role.TENANT_ADMIN) {
            // EnumSet.allOf — the owner holds everything in their own tenant by construction.
            assertThat(defaults).containsAll(GRANTED_PER_USER_ONLY);
        } else {
            assertThat(defaults)
                    .as("%s must not inherit private-note/recording/template/workflow access — "
                            + "grant it per user instead", role)
                    .doesNotContainAnyElementsOf(GRANTED_PER_USER_ONLY);
        }
    }

    @Test
    @DisplayName("COMM_READ is granted exactly where V2 PART 17 backfills it")
    void readGrantMirrorsTheBackfill() {
        // PART 17 filters on u.role IN ('MANAGER','TRAVEL_AGENT','ACCOUNTANT','SUB_AGENT'). These two
        // sets must stay identical, or a user's inbox visibility depends on whether their permission
        // row happens to exist.
        for (Role role : BACKFILLED_ROLES) {
            assertThat(Permission.defaultsFor(role))
                    .as("%s is in the PART 17 backfill's role filter, so defaultsFor must grant it too", role)
                    .contains(Permission.COMM_READ);
        }

        assertThat(Permission.defaultsFor(Role.STAFF))
                .as("STAFF is deny-by-default: no permissions until an admin grants them")
                .doesNotContain(Permission.COMM_READ);
        assertThat(Permission.defaultsFor(Role.SUPERADMIN))
                .as("the platform owner is not a tenant CRM user and holds no tenant permissions")
                .doesNotContain(Permission.COMM_READ);
    }

    @Test
    @DisplayName("a role holding COMM_READ must also hold CUSTOMER_READ — the backfill keys on it")
    void commReadImpliesCustomerRead() {
        for (Role role : Role.values()) {
            Set<Permission> defaults = Permission.defaultsFor(role);
            if (defaults.contains(Permission.COMM_READ)) {
                assertThat(defaults)
                        .as("%s: PART 17 only reaches users whose saved map has CUSTOMER_READ=true, so a "
                                + "role with COMM_READ but no CUSTOMER_READ would be unreachable by the "
                                + "backfill and would see an empty inbox with no error", role)
                        .contains(Permission.CUSTOMER_READ);
            }
        }
    }

    @Test
    @DisplayName("COMM_SEND reaches exactly MANAGER, TRAVEL_AGENT and SUB_AGENT")
    void sendGrantMirrorsTheBackfill() {
        // PART 17: CASE WHEN u.role IN ('MANAGER','TRAVEL_AGENT','SUB_AGENT').
        assertThat(Permission.defaultsFor(Role.MANAGER)).contains(Permission.COMM_SEND);
        assertThat(Permission.defaultsFor(Role.TRAVEL_AGENT)).contains(Permission.COMM_SEND);
        assertThat(Permission.defaultsFor(Role.SUB_AGENT)).contains(Permission.COMM_SEND);

        assertThat(Permission.defaultsFor(Role.ACCOUNTANT))
                .as("finance reads threads (a payment query arrives on WhatsApp like anything else) "
                        + "but does not correspond with customers on the agency's behalf")
                .doesNotContain(Permission.COMM_SEND);
    }

    @Test
    @DisplayName("COMM_CALL_LOG reaches exactly MANAGER and TRAVEL_AGENT")
    void callLogGrantMirrorsTheBackfill() {
        // PART 17: CASE WHEN u.role IN ('MANAGER','TRAVEL_AGENT').
        assertThat(Permission.defaultsFor(Role.MANAGER)).contains(Permission.COMM_CALL_LOG);
        assertThat(Permission.defaultsFor(Role.TRAVEL_AGENT)).contains(Permission.COMM_CALL_LOG);
        assertThat(Permission.defaultsFor(Role.ACCOUNTANT)).doesNotContain(Permission.COMM_CALL_LOG);
        assertThat(Permission.defaultsFor(Role.SUB_AGENT)).doesNotContain(Permission.COMM_CALL_LOG);
    }

    @Test
    @DisplayName("triage and analytics reach MANAGER only")
    void assignAndReportReachManagerOnly() {
        // PART 17: CASE WHEN u.role = 'MANAGER'.
        assertThat(Permission.defaultsFor(Role.MANAGER))
                .contains(Permission.COMM_ASSIGN, Permission.COMM_REPORT_VIEW);

        for (Role role : Set.of(Role.TRAVEL_AGENT, Role.ACCOUNTANT, Role.SUB_AGENT, Role.STAFF)) {
            assertThat(Permission.defaultsFor(role))
                    .as("%s: reassigning another agent's thread is a supervisor act, and tenant-wide "
                            + "response times are not this role's business", role)
                    .doesNotContain(Permission.COMM_ASSIGN, Permission.COMM_REPORT_VIEW);
        }
    }

    @Test
    @DisplayName("COMM_CHAT reaches every backfilled role — internal chat is gated by membership")
    void chatReachesEveryBackfilledRole() {
        // PART 17 grants COMM_CHAT unconditionally to the four backfilled roles, with scope 'all',
        // because internal chat is not row-scoped by assignment at all: CommAccessGuard gates it on
        // membership of the conversation. A scope narrower than 'all' here would be meaningless and
        // would read as though something were being restricted.
        for (Role role : BACKFILLED_ROLES) {
            assertThat(Permission.defaultsFor(role))
                    .as("%s is in the PART 17 role filter, which grants COMM_CHAT unconditionally", role)
                    .contains(Permission.COMM_CHAT);
        }
    }

    @Test
    @DisplayName("every COMM_* key is catalogued under one module label")
    void keysAreCataloguedTogether() {
        // The FE renders GET /api/permissions/catalog grouped by this label. A stray label would
        // scatter the module's toggles across two sections of the permission grid.
        for (Permission p : Permission.values()) {
            if (p.name().startsWith("COMM_")) {
                assertThat(p.getModule())
                        .as("%s must sit in the Communication group of the permission catalogue", p)
                        .isEqualTo("Communication");
            }
        }
    }
}
