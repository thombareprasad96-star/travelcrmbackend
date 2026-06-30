package com.crm.travelcrm.quotation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Minimal reference to a quotation — just enough for the lead list to offer a
 * "View / Download" action without shipping the whole summary:
 *   view     -> open /quotations/{publicId}
 *   download -> GET /api/quotations/{publicId}/pdf
 *
 * <p>A null {@code latestQuotation} on a lead means it has no quotation yet, so the
 * UI shows "Create Quotation" instead.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuotationRefDto {

    /** Quotation.publicId — used for both the view route and the /pdf download endpoint. */
    private UUID publicId;

    /** Computed grand total of this (latest) quotation — what the lead list shows as the deal value. */
    private BigDecimal grandTotal;
}