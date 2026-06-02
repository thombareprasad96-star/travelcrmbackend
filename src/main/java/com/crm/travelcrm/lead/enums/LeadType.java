package com.crm.travelcrm.lead.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum LeadType {
    FRESH_LEAD("Fresh Lead"),
    WARM_LEAD("Warm Lead"),
    HOT_LEAD("Hot Lead"),
    COLD_LEAD("Cold Lead");

    private final String displayName;

    LeadType(String displayName) { this.displayName = displayName; }

    @JsonValue
    public String getDisplayName() { return displayName; }

    @JsonCreator
    public static LeadType fromValue(String value) {
        for (LeadType type : values()) {
            if (type.displayName.equalsIgnoreCase(value) || type.name().equalsIgnoreCase(value)) return type;
        }
        throw new IllegalArgumentException("Unknown LeadType: " + value);
    }
}