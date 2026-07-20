package com.crm.travelcrm.quotation.dto;

import com.crm.travelcrm.quotation.enums.DiscountType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Customer-safe view of a quotation for the unauthenticated public share link
 * ({@code GET /api/public/quotations/{publicId}}).
 *
 * <p>This is a deliberate <b>whitelist</b>: only fields a customer may see are declared, so a field
 * later added to {@link QuotationResponseDto} can never silently leak here. In particular it omits the
 * agent's margin — {@code totals.markup} and the whole {@code pricing} block (raw discount / tax /
 * markup inputs) — plus internal metadata ({@code leadStage}, {@code quotationStage}, {@code notes},
 * {@code createdBy}, {@code leadId}, audit timestamps). The reused section types
 * ({@link QuotationResponseDto.FlightSection} etc.) carry only customer-facing sell prices, no cost or
 * margin, so they are safe to expose as-is (their free-text {@code notes} are redacted at projection).</p>
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PublicQuotationResponseDto {

    private UUID publicId;
    private String title;
    private String version;
    private Integer versionNumber;
    private String pdfUrl;
    private String coverImageUrl;
    /** CLASSIC/MODERN — the public weblink page branches its whole design on this. Never null. */
    private com.crm.travelcrm.quotation.enums.TemplateStyle templateStyle;

    private Integer quoteNo;
    private Integer nights;
    private Integer days;
    private Integer rooms;

    private QuotationResponseDto.Customer customer;

    private QuotationResponseDto.FlightSection flight;
    private QuotationResponseDto.HotelSection hotel;
    private QuotationResponseDto.SightseeingSection sightseeing;
    private QuotationResponseDto.CruiseSection cruise;
    private QuotationResponseDto.VehicleSection vehicle;
    private QuotationResponseDto.AddonSection addons;

    private List<String> inclusions;
    private List<String> exclusions;
    private List<String> paymentPolicies;
    private List<String> cancellationPolicies;
    private List<String> bookingTerms;

    private PublicTotals totals;

    /** The customer-facing money breakdown — identical to {@link QuotationResponseDto.Totals} minus {@code markup}. */
    @Data
    @Builder
    public static class PublicTotals {
        private BigDecimal subtotal;
        private DiscountType discountType;
        private BigDecimal discount;
        private BigDecimal discountAmount;
        private BigDecimal taxPercent;
        private BigDecimal taxAmount;
        private BigDecimal grandTotal;
        private BigDecimal addonsTotal;
        private BigDecimal perAdult;
        // NOTE: no `markup` — that is the agent's profit and must never reach the customer.
    }
}
