package com.crm.travelcrm.accounting.tax.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

/** Read view of an HSN/SAC rate row. Exposes {@code publicId} only — never the internal id. */
@Getter
@Builder
public class HsnSacRateDto {

    private final UUID publicId;
    private final String code;
    private final String description;
    private final String category;
    private final BigDecimal gstRatePct;
    private final BigDecimal cessPct;
    private final boolean itcEligible;
    private final boolean isDefault;
    private final boolean active;
}