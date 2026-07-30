package com.crm.travelcrm.lead.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LeadTypeTest {

    @Test
    void fromValueAcceptsDisplayLabels() {
        assertThat(LeadType.fromValue("Fresh Lead")).isEqualTo(LeadType.FRESH_LEAD);
        assertThat(LeadType.fromValue("Repeat Customer")).isEqualTo(LeadType.REPEAT_CUSTOMER);
        assertThat(LeadType.fromValue("Corporate")).isEqualTo(LeadType.CORPORATE);
        assertThat(LeadType.fromValue("VIP")).isEqualTo(LeadType.VIP);
    }

    @Test
    void fromValueAcceptsEnumNamesCaseInsensitive() {
        assertThat(LeadType.fromValue("fresh_lead")).isEqualTo(LeadType.FRESH_LEAD);
        assertThat(LeadType.fromValue("repeat_customer")).isEqualTo(LeadType.REPEAT_CUSTOMER);
        assertThat(LeadType.fromValue("corporate")).isEqualTo(LeadType.CORPORATE);
        assertThat(LeadType.fromValue("vip")).isEqualTo(LeadType.VIP);
    }

    @Test
    void displayNamesAreStable() {
        assertThat(LeadType.FRESH_LEAD.getDisplayName()).isEqualTo("Fresh Lead");
        assertThat(LeadType.REPEAT_CUSTOMER.getDisplayName()).isEqualTo("Repeat Customer");
        assertThat(LeadType.CORPORATE.getDisplayName()).isEqualTo("Corporate");
        assertThat(LeadType.VIP.getDisplayName()).isEqualTo("VIP");
    }
}
