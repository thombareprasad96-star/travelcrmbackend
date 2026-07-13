package com.crm.travelcrm.booking.cancellation.dto;

import com.crm.travelcrm.booking.cancellation.enums.DeductionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

/** One slab of a cancellation policy, as sent/returned over the API. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CancellationPolicyBandDto {

    /** Inclusive lower bound in days before departure (>= 0). One band MUST be 0 (the floor slab). */
    @NotNull(message = "minDaysBeforeDeparture is required")
    @Min(value = 0, message = "minDaysBeforeDeparture cannot be negative")
    private Integer minDaysBeforeDeparture;

    @NotNull(message = "deductionType is required (PERCENT or FLAT)")
    private DeductionType deductionType;

    /** PERCENT: 0–100. FLAT: >= 0. Cross-field range is validated in the service. */
    @NotNull(message = "deductionValue is required")
    @DecimalMin(value = "0.0", message = "deductionValue cannot be negative")
    private BigDecimal deductionValue;
}