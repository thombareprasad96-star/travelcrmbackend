package com.crm.travelcrm.lead.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum LeadSource {

    WHATSAPP("WhatsApp"),
    FACEBOOK("Facebook"),
    INSTAGRAM("Instagram"),
    SOCIAL_MEDIA("Social Media"),
    WEBSITE("Website"),
    REFERRAL("Referral"),
    WALK_IN("Walk-In"),
    PHONE_CALL("Phone Call"),
    EMAIL("Email"),
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
        for (LeadSource source : values()) {
            if (source.displayName.equalsIgnoreCase(value)
                    || source.name().equalsIgnoreCase(value)) {
                return source;
            }
        }
        throw new IllegalArgumentException("Unknown LeadSource: " + value);
    }
}