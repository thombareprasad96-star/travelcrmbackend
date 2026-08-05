package com.crm.travelcrm.quotationtemplate.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * "Turn this quotation into a reusable package."
 *
 * <p>Everything but {@link #quotationId} is an override on what the server derived from the
 * quotation itself — the preview endpoint returns those derived values first, so the modal is
 * editing real numbers rather than guessing at them. A null override means "keep what you derived",
 * which is why every field here is boxed.
 */
@Data
public class SaveAsTemplateRequest {

    /** The saved quotation to capture. Must be visible to the caller. */
    @NotNull(message = "quotationId is required")
    private UUID quotationId;

    @Size(max = 150, message = "Name must not exceed 150 characters")
    private String name;

    @Size(max = 5000)
    private String description;

    /** Defaults to true. Inactive templates stay listable but never appear in match results. */
    private Boolean active;

    @Min(value = 1, message = "Hotel tier must be between 1 and 5")
    @Max(value = 5, message = "Hotel tier must be between 1 and 5")
    private Integer hotelTier;

    /**
     * Indicative package price. Derived from the quotation's grand total, which was priced for that
     * quotation's party size — so it is offered as an editable default, never silently trusted.
     */
    @DecimalMin(value = "0.0", inclusive = false, message = "Base price must be greater than 0")
    @Digits(integer = 13, fraction = 2)
    private BigDecimal basePrice;

    /** Months the package is sold in. Omit or leave empty for a year-round package. */
    private Set<@Min(1) @Max(12) Integer> seasonMonths;

    /**
     * Update this existing template instead of creating a new one — the "you already have a very
     * similar package" path. The template must belong to the caller's tenant; its children are
     * replaced wholesale, exactly as a normal update does.
     */
    private UUID updateTemplateId;
}
