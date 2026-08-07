package com.crm.travelcrm.quotationtemplate.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One suggested package, with the arithmetic that produced its badge. The agent must always be able
 * to expand a "78 %" and read why — a score they cannot interrogate is a score they will not trust.
 */
@Data
@Builder
public class TemplateMatchResponse {

    private UUID id;
    private String name;
    private String description;
    private String coverImageUrl;

    private Integer durationNights;
    private Integer durationDays;
    private Integer hotelTier;
    private BigDecimal basePrice;
    private List<String> cities;
    private List<Integer> seasonMonths;

    /** Section keys this package covers — empty when the template never declared them. */
    private List<String> services;

    /** How many quotations have been cloned from this template. Drives the "used N times" chip. */
    private Integer timesApplied;

    /** 0–100, renormalized over the components that applied to this lead. */
    private int matchPercentage;

    /**
     * True for the cold-start fallback rows — the most-used packages we offer when NOTHING cleared
     * the score threshold. The client must present these separately; they are "here is what you
     * have", not "here is what fits".
     */
    private boolean belowThreshold;

    private List<Component> components;

    @Data
    @Builder
    public static class Component {
        /** {@code destination|duration|hotelTier|budget|season|services} */
        private String key;
        private String label;
        /** Configured weight before renormalization, e.g. 0.35. */
        private double weight;
        /** False ⇒ this dimension was excluded from the total and its weight redistributed. */
        private boolean applicable;
        /** 0–100. Meaningless when {@code applicable} is false. */
        private int score;
        /** Plain-English reason, e.g. "3 of 4 requested cities covered". */
        private String detail;
    }
}