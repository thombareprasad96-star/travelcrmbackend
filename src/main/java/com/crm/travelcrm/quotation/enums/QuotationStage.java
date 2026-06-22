package com.crm.travelcrm.quotation.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Lifecycle stage of a quotation.
 *
 * <p>The frontend sends/reads the human label ("Draft", "Sent", "Approved",
 * "Rejected"). {@link #getLabel()} ({@code @JsonValue}) serialises that label,
 * while {@link #fromValue(String)} ({@code @JsonCreator}) accepts either the
 * label or the enum name, case-insensitively — so it also works for the
 * {@code PATCH /{publicId}/stage?stage=Sent} query parameter.
 */
public enum QuotationStage {

    DRAFT("Draft"),
    SENT("Sent"),
    APPROVED("Approved"),
    REJECTED("Rejected");

    private final String label;

    QuotationStage(String label) {
        this.label = label;
    }

    @JsonValue
    public String getLabel() {
        return label;
    }

    @JsonCreator
    public static QuotationStage fromValue(String value) {
        if (value == null || value.isBlank()) {
            return DRAFT;
        }
        String trimmed = value.trim();
        for (QuotationStage stage : values()) {
            if (stage.name().equalsIgnoreCase(trimmed) || stage.label.equalsIgnoreCase(trimmed)) {
                return stage;
            }
        }
        throw new IllegalArgumentException(
                "Invalid quotation stage: '" + value + "'. Allowed: Draft, Sent, Approved, Rejected");
    }
}