package com.crm.travelcrm.lead.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum LeadSource {
    SOCIAL_MEDIA("Social Media"),
    WEBSITE("Website"),
    GOOGLE_ADS("Google Ads"),
    FACEBOOK("Facebook"),
    INSTAGRAM("Instagram"),
    WHATSAPP("WhatsApp"),
    REFERRAL("Referral"),
    DIRECT_CALL("Direct Call"),
    OTHER("Other");

    private final String displayName;

    LeadSource(String displayName) {
        this.displayName = displayName;
    }

    @JsonValue
    public String getDisplayName() {
        return displayName;
    }

    @JsonCreator
    public static LeadSource fromValue(String value) {
        if (value == null) return null;
        for (LeadSource source : values()) {
            if (source.displayName.equalsIgnoreCase(value) || source.name().equalsIgnoreCase(value)) {
                return source;
            }
        }
        throw new IllegalArgumentException("Unknown LeadSource: " + value);
    }
}