package com.crm.travelcrm.quotation.dto;

import com.crm.travelcrm.quotation.enums.TemplateStyle;
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

    /** Version label ("v1.0") — the lead list puts it in the WhatsApp/email share text. */
    private String version;

    /**
     * The design the customer's share link currently renders. The lead row's Weblink button
     * persists a style before opening, and it can only tell whether that PATCH is needed if it
     * knows the stored value — without this every open re-saved the style it already had.
     */
    private TemplateStyle templateStyle;

    /**
     * The agent's markup on this quotation — the only profit figure a quotation carries, since
     * there is no vendor-cost column to subtract. Named for what the lead list's column has always
     * been called; it is markup, not a computed revenue-minus-cost margin. Absent (not zero) when
     * no markup was ever entered, so the column can show "—" rather than a page of ₹0.00.
     */
    private BigDecimal margin;

    /**
     * How many times an actual client opened the share link. EXTERNAL views only — the tenant's own
     * staff opening their own quotation (ViewerType.HOME) is not customer interest and must not
     * inflate the badge the agent reads as "they looked at it".
     */
    private Long viewCount;
}