package com.crm.travelcrm.lead.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LeadTypeTest {

    @Test
    void fromValueAcceptsNewLabels() {
        assertThat(LeadType.fromValue("Fresh")).isEqualTo(LeadType.FRESH);
        assertThat(LeadType.fromValue("Hot")).isEqualTo(LeadType.HOT);
        assertThat(LeadType.fromValue("Warm")).isEqualTo(LeadType.WARM);
        assertThat(LeadType.fromValue("Cold")).isEqualTo(LeadType.COLD);
    }

    @Test
    void fromValueMapsLegacyLabelsToNewTypes() {
        assertThat(LeadType.fromValue("Fresh Lead")).isEqualTo(LeadType.FRESH);
        assertThat(LeadType.fromValue("Repeat Customer")).isEqualTo(LeadType.WARM);
        assertThat(LeadType.fromValue("Corporate")).isEqualTo(LeadType.HOT);
        assertThat(LeadType.fromValue("VIP")).isEqualTo(LeadType.HOT);
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyConstantsRenderNewLabels() {
        assertThat(LeadType.FRESH_LEAD.getDisplayName()).isEqualTo("Fresh");
        assertThat(LeadType.REPEAT_CUSTOMER.getDisplayName()).isEqualTo("Warm");
        assertThat(LeadType.CORPORATE.getDisplayName()).isEqualTo("Hot");
        assertThat(LeadType.VIP.getDisplayName()).isEqualTo("Hot");
    }
}
