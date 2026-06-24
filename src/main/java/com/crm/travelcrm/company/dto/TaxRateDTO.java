package com.crm.travelcrm.company.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Value
@Builder
public class TaxRateDTO {
    UUID          publicId;
    String        type;
    BigDecimal    rate;
    String        calculation;
    String        effectiveFrom;   // ISO date string "2026-06-18"
    String        effectiveTo;
    String        description;
    boolean       isActive;
    LocalDateTime createdAt;
}