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
 * Pins the fleet money-authority rules that are otherwise only prose — in a javadoc comment and in a
 * SQL comment in {@code V2__lead_code.sql} PART 6.
 *
 * <p><b>Why this test exists.</b> Fleet money has TWO independent grant paths:
 * {@link Permission#defaultsFor(Role)} for a user who has never had their permission screen saved,
 * and the PART 6 backfill for a user who has. An adversarial review caught them disagreeing on the
 * day they were written — {@code defaultsFor(MANAGER)} handed out {@code FLEET_MONEY_SETTLE} while
 * the migration's own comment declared it was never auto-granted. The consequence is not abstract:
 * two MANAGERs in one tenant would hold different authority over an irreversible Rs 40,000 driver
 * payout depending on whether anyone had ever clicked Save on their profile.
 *
 * <p>Prose cannot hold that correspondence. This does.
 */
class FleetMoneyPermissionDefaultsTest {

    /** Authorities that move or freeze money, and must never be inherited from an operational role. */
    private static final Set<Permission> MONEY_AUTHORITY =
            EnumSet.of(Permission.FLEET_MONEY_SETTLE, Permission.FLEET_PERIOD_CLOSE);

    @ParameterizedTest
    @EnumSource(Role.class)
    @DisplayName("only TENANT_ADMIN and ACCOUNTANT hold fleet money authority by role default")
    void moneyAuthorityIsNeverInherited(Role role) {
        Set<Permission> defaults = Permission.defaultsFor(role);

        if (role == Role.TENANT_ADMIN || role == Role.ACCOUNTANT) {
            assertThat(defaults).containsAll(MONEY_AUTHORITY);
        } else {
            assertThat(defaults)
                    .as("%s must not inherit settle/period-close — grant it per user instead", role)
                    .doesNotContainAnyElementsOf(MONEY_AUTHORITY);
        }
    }

    @Test
    @DisplayName("FLEET_MONEY_READ is granted exactly where V2 PART 6 backfills it: MANAGER + ACCOUNTANT")
    void readGrantMirrorsTheBackfill() {
        // PART 6 filters on u.role IN ('MANAGER','ACCOUNTANT'). These two sets must stay identical,
        // or a user's money visibility depends on whether their permission row happens to exist.
        assertThat(Permission.defaultsFor(Role.MANAGER)).contains(Permission.FLEET_MONEY_READ);
        assertThat(Permission.defaultsFor(Role.ACCOUNTANT)).contains(Permission.FLEET_MONEY_READ);

        assertThat(Permission.defaultsFor(Role.TRAVEL_AGENT))
                .as("sales role: plans and runs trips, but tenant-wide cash positions are not its business")
                .doesNotContain(Permission.FLEET_MONEY_READ);
        assertThat(Permission.defaultsFor(Role.STAFF)).doesNotContain(Permission.FLEET_MONEY_READ);
        assertThat(Permission.defaultsFor(Role.SUB_AGENT))
                .as("B2B partner: no fleet surface at all")
                .doesNotContain(Permission.FLEET_MONEY_READ);
    }

    @Test
    @DisplayName("a role holding fleet money read must also hold fleet read — the backfill keys on it")
    void moneyReadImpliesFleetRead() {
        for (Role role : Role.values()) {
            Set<Permission> defaults = Permission.defaultsFor(role);
            if (defaults.contains(Permission.FLEET_MONEY_READ)) {
                assertThat(defaults)
                        .as("%s: PART 6 only reaches users whose saved map has FLEET_READ=true, so a "
                                + "role with money-read but no fleet-read would be unreachable by the backfill", role)
                        .contains(Permission.FLEET_READ);
            }
        }
    }
}
