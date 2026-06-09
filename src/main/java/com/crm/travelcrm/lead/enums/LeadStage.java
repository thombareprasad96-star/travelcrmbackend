package com.crm.travelcrm.lead.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum LeadStage {
    NEW_LEAD("New Lead"),
    CONTACTED("Contacted"),
    PROPOSAL_SENT("Proposal Sent"),
    NEGOTIATION("Negotiation"),
    CONVERTED("Converted"),
    LOST("Lost");

    private final String displayName;

    LeadStage(String displayName) { this.displayName = displayName; }

    @JsonValue
    public String getDisplayName() { return displayName; }

    @JsonCreator
    public static LeadStage fromValue(String value) {
        for (LeadStage stage : values()) {
            if (stage.displayName.equalsIgnoreCase(value) || stage.name().equalsIgnoreCase(value)) return stage;
        }
        throw new IllegalArgumentException("Unknown LeadStage: " + value);
    }
}