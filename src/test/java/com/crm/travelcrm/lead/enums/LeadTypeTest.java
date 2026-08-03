package com.crm.travelcrm.lead.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeadTypeTest {

    @Test
    void fromValueAcceptsDisplayLabels() {
        assertThat(LeadType.fromValue("Fresh")).isEqualTo(LeadType.FRESH);
        assertThat(LeadType.fromValue("Hot")).isEqualTo(LeadType.HOT);
        assertThat(LeadType.fromValue("Warm")).isEqualTo(LeadType.WARM);
        assertThat(LeadType.fromValue("Cold")).isEqualTo(LeadType.COLD);
    }

    @Test
    void fromValueAcceptsEnumNamesCaseInsensitive() {
        assertThat(LeadType.fromValue("fresh")).isEqualTo(LeadType.FRESH);
        assertThat(LeadType.fromValue("HOT")).isEqualTo(LeadType.HOT);
        assertThat(LeadType.fromValue("wArM")).isEqualTo(LeadType.WARM);
        assertThat(LeadType.fromValue("cold")).isEqualTo(LeadType.COLD);
    }

    /**
     * The display name IS the wire format ({@code @JsonValue}). A change here silently breaks every
     * stored frontend string and every integration payload, so it is pinned.
     */
    @Test
    void displayNamesAreStable() {
        assertThat(LeadType.FRESH.getDisplayName()).isEqualTo("Fresh");
        assertThat(LeadType.HOT.getDisplayName()).isEqualTo("Hot");
        assertThat(LeadType.WARM.getDisplayName()).isEqualTo("Warm");
        assertThat(LeadType.COLD.getDisplayName()).isEqualTo("Cold");
    }

    /** Exactly four, and no more: the create form's dropdown is this list. */
    @Test
    void vocabularyIsExactlyTheFourPriorityLevels() {
        assertThat(LeadType.values())
                .containsExactly(LeadType.FRESH, LeadType.HOT, LeadType.WARM, LeadType.COLD);
    }

    /**
     * Blank must yield null, not throw. An untouched dropdown posts "", and throwing here happens
     * inside Jackson deserialization — before bean validation — which produced an opaque 400 with no
     * fieldErrors for the form to render, and made {@code @NotNull("Lead type is required")}
     * unreachable.
     */
    @Test
    void blankYieldsNullSoValidationCanReportIt() {
        assertThat(LeadType.fromValue(null)).isNull();
        assertThat(LeadType.fromValue("")).isNull();
        assertThat(LeadType.fromValue("   ")).isNull();
    }

    @Test
    void genuinelyUnknownValueStillThrows() {
        assertThatThrownBy(() -> LeadType.fromValue("Lukewarm"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Lukewarm");
    }
}
