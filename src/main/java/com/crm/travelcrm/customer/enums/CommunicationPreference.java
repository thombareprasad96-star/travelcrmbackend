package com.crm.travelcrm.customer.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Preferred channel to reach the customer. Mirrors the comm-preference chips in
 * {@code CustomerInformation.jsx} (WhatsApp / SMS / Email / Phone Call / All Channels).
 */
public enum CommunicationPreference {

    WHATSAPP("WhatsApp"),
    SMS("SMS"),
    EMAIL("Email"),
    PHONE_CALL("Phone Call"),
    ALL_CHANNELS("All Channels");

    private final String displayName;

    CommunicationPreference(String displayName) {
        this.displayName = displayName;
    }

    @JsonValue
    public String getDisplayName() {
        return displayName;
    }

    @JsonCreator
    public static CommunicationPreference fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null; // optional field — tolerate empty
        }
        for (CommunicationPreference pref : values()) {
            if (pref.displayName.equalsIgnoreCase(value) || pref.name().equalsIgnoreCase(value)) {
                return pref;
            }
        }
        throw new IllegalArgumentException("Unknown communication preference: " + value);
    }
}