package com.crm.travelcrm.permission;

import com.crm.travelcrm.auth.enums.Role;
import com.crm.travelcrm.permission.enums.Permission;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookingProfitPermissionDefaultsTest {

    @Test
    void profitVisibilityIsRestrictedToManagerAndAccountantDefaults() {
        assertThat(Permission.defaultsFor(Role.MANAGER))
                .contains(Permission.BOOKING_PROFIT_READ);
        assertThat(Permission.defaultsFor(Role.ACCOUNTANT))
                .contains(Permission.BOOKING_PROFIT_READ);

        assertThat(Permission.defaultsFor(Role.TRAVEL_AGENT))
                .doesNotContain(Permission.BOOKING_PROFIT_READ);
        assertThat(Permission.defaultsFor(Role.STAFF))
                .doesNotContain(Permission.BOOKING_PROFIT_READ);
        assertThat(Permission.defaultsFor(Role.SUB_AGENT))
                .doesNotContain(Permission.BOOKING_PROFIT_READ);
    }
}
