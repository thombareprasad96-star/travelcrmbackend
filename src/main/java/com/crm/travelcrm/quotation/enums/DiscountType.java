package com.crm.travelcrm.quotation.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How the discount on a quotation is interpreted.
 *
 * <p>Frontend ({@code Summarypricingtab.jsx}) sends {@code "%"} or {@code "Fixed"}.
 * {@code PERCENT} treats the discount value as a percentage of the subtotal;
 * {@code FIXED} treats it as an absolute amount.
 */
public enum DiscountType {

    PERCENT("%"),
    FIXED("Fixed");

    private final String label;

    DiscountType(String label) {
        this.label = label;
    }

    @JsonValue
    public String getLabel() {
        return label;
    }

    @JsonCreator
    public static DiscountType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return FIXED;
        }
        String trimmed = value.trim();
        if ("%".equals(trimmed) || "PERCENT".equalsIgnoreCase(trimmed) || "PERCENTAGE".equalsIgnoreCase(trimmed)) {
            return PERCENT;
        }
        if ("Fixed".equalsIgnoreCase(trimmed) || "FLAT".equalsIgnoreCase(trimmed)) {
            return FIXED;
        }
        throw new IllegalArgumentException(
                "Invalid discount type: '" + value + "'. Allowed: % or Fixed");
    }
}