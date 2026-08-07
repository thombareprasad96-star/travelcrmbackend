package com.crm.travelcrm.permission;

import com.crm.travelcrm.auth.enums.Role;
import com.crm.travelcrm.permission.enums.Permission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the claim-window authority rules, which otherwise exist only as prose — a javadoc on
 * {@link Permission} and a SQL comment in {@code V2__lead_code.sql} PART 18.
 *
 * <p><b>Why this test exists.</b> Exactly the problem {@code FleetMoneyPermissionDefaultsTest} was
 * written for: two independent grant paths that must agree. {@link Permission#defaultsFor(Role)}
 * serves a user whose permission screen was never saved; the PART 18 backfill serves a user whose
 * was. A saved map wins outright ({@code EffectivePermissionResolver}), so if the two disagree, two
 * TRAVEL_AGENTs in the same tenant differ on whether they can claim a lead — purely by whether
 * anyone ever clicked Save on their profile. Nobody would ever guess that from the symptom.
 */
class LeadClaimPermissionDefaultsTest {

    @Test
    @DisplayName("LEAD_CLAIM is granted exactly where V2 PART 18 backfills it: MANAGER + TRAVEL_AGENT")
    void claimGrantMirrorsTheBackfill() {
        // PART 18 filters on u.role IN ('MANAGER','TRAVEL_AGENT'). These must stay identical.
        assertThat(Permission.defaultsFor(Role.MANAGER)).contains(Permission.LEAD_CLAIM);
        assertThat(Permission.defaultsFor(Role.TRAVEL_AGENT)).contains(Permission.LEAD_CLAIM);

        assertThat(Permission.defaultsFor(Role.SUB_AGENT))
                .as("AssignableUserResolver excludes sub-agents from owning a parent tenant's lead; "
                        + "a claim grant would be a second door into exactly what that excludes")
                .doesNotContain(Permission.LEAD_CLAIM);
        assertThat(Permission.defaultsFor(Role.STAFF))
                .as("STAFF is deny-by-default — everything is granted explicitly")
                .doesNotContain(Permission.LEAD_CLAIM);
        assertThat(Permission.defaultsFor(Role.ACCOUNTANT))
                .as("no lead surface at all")
                .doesNotContain(Permission.LEAD_CLAIM);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    @DisplayName("post-lock reassign authority is never inherited by an operational role")
    void reassignAuthorityIsNeverInherited(Role role) {
        Set<Permission> defaults = Permission.defaultsFor(role);

        if (role == Role.TENANT_ADMIN || role == Role.MANAGER) {
            assertThat(defaults).contains(Permission.LEAD_REASSIGN_LOCKED);
        } else {
            assertThat(defaults)
                    .as("%s must not inherit reassign-after-lock — once a colleague has spoken to "
                            + "the customer, moving the lead is a manager's call", role)
                    .doesNotContain(Permission.LEAD_REASSIGN_LOCKED);
        }
    }

    @Test
    @DisplayName("a role that may claim must also be able to read leads — the backfill keys on it")
    void claimImpliesLeadRead() {
        for (Role role : Role.values()) {
            Set<Permission> defaults = Permission.defaultsFor(role);
            if (defaults.contains(Permission.LEAD_CLAIM)
                    || defaults.contains(Permission.LEAD_REASSIGN_LOCKED)) {
                assertThat(defaults)
                        .as("%s: PART 18 only reaches users whose saved map has LEAD_READ=true, so a "
                                + "role with a claim key but no lead-read would be unreachable by the "
                                + "backfill and would silently keep the old behaviour", role)
                        .contains(Permission.LEAD_READ);
            }
        }
    }

    @Test
    @DisplayName("reassign-after-lock implies claim — a supervisor can do anything an agent can")
    void reassignImpliesClaim() {
        for (Role role : Role.values()) {
            Set<Permission> defaults = Permission.defaultsFor(role);
            if (defaults.contains(Permission.LEAD_REASSIGN_LOCKED)) {
                assertThat(defaults)
                        .as("%s holds the stronger post-lock authority but not the weaker open-lead "
                                + "one — a manager who cannot claim a fresh lead but can take a "
                                + "contacted one is backwards", role)
                        .contains(Permission.LEAD_CLAIM);
            }
        }
    }
}
