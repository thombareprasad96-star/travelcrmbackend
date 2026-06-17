package com.crm.travelcrm.customer.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Loyalty tier used for rewards segmentation. Matches the tier selector and
 * badges in the React UI (Bronze / Silver / Gold / Platinum).
 */
public enum LoyaltyTier {

    BRONZE("Bronze"),
    SILVER("Silver"),
    GOLD("Gold"),
    PLATINUM("Platinum");

    private final String displayName;

    LoyaltyTier(String displayName) {
        this.displayName = displayName;
    }

    @JsonValue
    public String getDisplayName() {
        return displayName;
    }

    @JsonCreator
    public static LoyaltyTier fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Loyalty tier must not be empty");
        }
        for (LoyaltyTier tier : values()) {
            if (tier.displayName.equalsIgnoreCase(value) || tier.name().equalsIgnoreCase(value)) {
                return tier;
            }
        }
        throw new IllegalArgumentException("Unknown loyalty tier: " + value);
    }
}