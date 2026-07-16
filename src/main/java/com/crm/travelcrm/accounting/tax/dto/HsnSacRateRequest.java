package com.crm.travelcrm.accounting.tax.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Create/update payload for an HSN/SAC rate row. */
@Getter
@Setter
public class HsnSacRateRequest {

    @NotBlank
    @Size(max = 12)
    private String code;

    @Size(max = 200)
    private String description;

    @Size(max = 60)
    private String category;

    @DecimalMin(value = "0.0")
    @DecimalMax(value = "100.0")
    private BigDecimal gstRatePct;

    @DecimalMin(value = "0.0")
    @DecimalMax(value = "100.0")
    private BigDecimal cessPct;

    private Boolean itcEligible;

    /** Mark this row as the tenant's default rate (unsets any other default). */
    private Boolean isDefault;

    private Boolean active;
}