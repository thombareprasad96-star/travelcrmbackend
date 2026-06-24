package com.crm.travelcrm.company.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class TaxRateCreateRequest {

    @NotBlank(message = "Tax type is required")
    @Size(max = 50)
    private String type;

    @NotNull(message = "Rate is required")
    @DecimalMin(value = "0.0", message = "Rate must be ≥ 0")
    private BigDecimal rate;

    @Size(max = 20)
    private String calculation;

    @NotNull(message = "Effective-from date is required")
    private LocalDate effectiveFrom;

    @Size(max = 500)
    private String description;
}