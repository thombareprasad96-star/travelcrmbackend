package com.crm.travelcrm.quotationtemplate.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Ask "which packages fit this lead?".
 *
 * <p>Everything but {@code leadId} is an override the agent can type into the match panel. The
 * server reads cities, nights, budget and travel month straight off the lead; the overrides let an
 * agent explore ("what if they had ₹1.5 L?") without editing the lead. {@link #hotelTier} is not an
 * override at all — a {@code Lead} has no star-preference column, so it is the one dimension that
 * only ever comes from here.
 */
@Data
public class TemplateMatchRequest {

    @NotNull(message = "leadId is required")
    private UUID leadId;

    @Min(1) @Max(5)
    private Integer hotelTier;

    @Min(1) @Max(365)
    private Integer durationNights;

    @DecimalMin(value = "0.0", inclusive = false)
    @Digits(integer = 13, fraction = 2)
    private BigDecimal budget;

    @Min(1) @Max(12)
    private Integer travelMonth;

    /** How many suggestions to return. Defaults to 10. */
    @Min(1) @Max(50)
    private Integer limit;
}