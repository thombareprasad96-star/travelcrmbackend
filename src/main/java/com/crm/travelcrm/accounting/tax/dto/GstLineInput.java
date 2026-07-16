package com.crm.travelcrm.accounting.tax.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One taxable line handed to the {@link com.crm.travelcrm.accounting.tax.service.GstCalculator}:
 * a taxable value plus the HSN/SAC code and GST rate that apply to it. Assembled from a booking's
 * service items (or a single line from the booking amount) before tax is computed.
 */
@Getter
@Builder
public class GstLineInput {

    private final String description;
    private final String hsnSac;
    private final BigDecimal taxableValue;
    private final BigDecimal gstRatePct;
    private final BigDecimal cessPct;
    private final boolean itcEligible;
    private final Long serviceItemId;
    private final UUID serviceItemPublicId;
}