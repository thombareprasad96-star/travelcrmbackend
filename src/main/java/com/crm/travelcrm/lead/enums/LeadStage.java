package com.crm.travelcrm.lead.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum LeadStage {

    NEW_LEAD("New Lead"),
    CONTACTED("Contacted"),
    FOLLOW_UP("Follow Up"),
    QUALIFIED("Qualified"),
    PROPOSAL_SENT("Proposal Sent"),
    CONVERTED("Converted"),
    // Re-activated after a booking conversion was cancelled ("move back to lead").
    REOPENED("Reopened"),
    LOST("Lost");

    private final String displayName;

    LeadStage(String displayName) {
        this.displayName = displayName;
    }

    @JsonValue
    public String getDisplayName() {
        return displayName;
    }

    @JsonCreator
    public static LeadStage fromValue(String value) {
        // Null/blank yields null so bean validation reports it as a field error, rather than
        // Jackson throwing first and producing a 400 the form cannot place beside an input.
        if (value == null || value.isBlank()) return null;
        for (LeadStage stage : values()) {
            if (stage.displayName.equalsIgnoreCase(value)
                    || stage.name().equalsIgnoreCase(value)) {
                return stage;
            }
        }
        throw new IllegalArgumentException("Unknown LeadStage: " + value);
    }
}