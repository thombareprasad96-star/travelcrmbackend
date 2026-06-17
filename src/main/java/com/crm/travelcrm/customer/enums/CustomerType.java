package com.crm.travelcrm.customer.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Customer classification.
 *
 * <p>The wire format (JSON) uses the human-friendly {@code displayName} the React
 * UI already sends ("VIP", "Corporate", …), while the database stores the stable
 * enum constant name. This decouples the persisted value from UI copy changes.</p>
 *
 * <p>The set is the union of the two front-end screens: {@code CustomerInformation}
 * offers Individual/Corporate/VIP/Group/Agent, while {@code AllCustomers} offers
 * Regular/Corporate/VIP — so all six are accepted.</p>
 */
public enum CustomerType {

    INDIVIDUAL("Individual"),
    REGULAR("Regular"),
    CORPORATE("Corporate"),
    VIP("VIP"),
    GROUP("Group"),
    AGENT("Agent");

    private final String displayName;

    CustomerType(String displayName) {
        this.displayName = displayName;
    }

    @JsonValue
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Lenient parser used for both JSON deserialization and {@code ?type=} query
     * params — accepts either the display name or the raw enum constant.
     */
    @JsonCreator
    public static CustomerType fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Customer type must not be empty");
        }
        for (CustomerType type : values()) {
            if (type.displayName.equalsIgnoreCase(value) || type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown customer type: " + value);
    }
}