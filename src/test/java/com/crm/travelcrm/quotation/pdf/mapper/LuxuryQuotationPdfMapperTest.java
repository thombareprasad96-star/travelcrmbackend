package com.crm.travelcrm.quotation.pdf.mapper;

import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.company.repository.CompanyRepository;
import com.crm.travelcrm.quotation.dto.QuotationResponseDto;
import com.crm.travelcrm.quotation.enums.DiscountType;
import com.crm.travelcrm.quotation.enums.QuotationStage;
import com.crm.travelcrm.quotation.pdf.dto.LuxuryQuotationPdfDto;
import com.crm.travelcrm.subagent.service.SubAgentBrandingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the LUXURY document says, given what the quotation holds.
 *
 * <p>No Spring context and no database: the mapper is built by hand with empty repositories, which
 * is exactly the state the PUBLIC share-link path runs in ({@code TenantContext} null, no Company
 * row, no sub-agent brand). That is deliberately the harshest fixture — if the mapper needs
 * something it cannot get there, it shows up here rather than as a broken customer-facing PDF.
 *
 * <p>The assertions cluster around one theme: <b>the mapper formats, it never invents and never
 * calculates</b>. Zeroes stay printable, absent data stays absent, and every rupee figure is the one
 * {@code Totals} already carried.
 */
class LuxuryQuotationPdfMapperTest {

    private LuxuryQuotationPdfMapper mapper;

    @BeforeEach
    void setUp() {
        CompanyRepository companies = mock(CompanyRepository.class);
        when(companies.findByTenantId(anyLong())).thenReturn(Optional.empty());

        SubAgentBrandingService branding = mock(SubAgentBrandingService.class);
        when(branding.resolve(any())).thenReturn(Optional.empty());

        UserRepository users = mock(UserRepository.class);

        mapper = new LuxuryQuotationPdfMapper(
                companies, branding, users,
                new LuxuryDisplayFormat(), new LuxuryPdfPaginator(),
                "TravelCRM", "Your Journey, Our Passion", "", "", "", "", "");
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static QuotationResponseDto.QuotationResponseDtoBuilder base() {
        return QuotationResponseDto.builder()
                .publicId(UUID.randomUUID())
                .title("Kashmir Delight")
                .quoteNo(45)
                .createdAt(LocalDateTime.of(2026, 3, 1, 10, 0))
                .nights(6)
                .days(7)
                .rooms(2)
                .quotationStage(QuotationStage.SENT)
                .customer(QuotationResponseDto.Customer.builder()
                        .name("Asha Nair")
                        .destination("Kashmir")
                        .travelDate(LocalDate.of(2026, 8, 20))
                        .adults(2).children(1).infants(0)
                        .build())
                .totals(totals("100000", "0", "0", "0"));
    }

    private static QuotationResponseDto.Totals totals(String subtotal, String discount,
                                                      String taxPercent, String taxAmount) {
        return QuotationResponseDto.Totals.builder()
                .subtotal(new BigDecimal(subtotal))
                .discountType(DiscountType.PERCENT)
                .discount(BigDecimal.ZERO)
                .discountAmount(new BigDecimal(discount))
                .markup(BigDecimal.ZERO)
                .taxPercent(new BigDecimal(taxPercent))
                .taxAmount(new BigDecimal(taxAmount))
                .grandTotal(new BigDecimal(subtotal))
                .build();
    }

    private static QuotationResponseDto.HotelItem hotel(String name, String image) {
        return QuotationResponseDto.HotelItem.builder()
                .name(name).city("Srinagar").stars(4)
                .checkIn("2026-08-20").checkOut("2026-08-24")
                .roomType("Deluxe").mealPlan("MAP").rooms(2).refundable(true)
                .imagePath(image)
                .build();
    }

    private static QuotationResponseDto.DayItem day(int n, String attraction, String description) {
        return QuotationResponseDto.DayItem.builder()
                .day(n).date("2026-08-" + (19 + n))
                .activities(List.of(QuotationResponseDto.Activity.builder()
                        .attraction(attraction).description(description)
                        .meals(List.of("Breakfast")).transfer("Private car")
                        .build()))
                .build();
    }

    // ── Headline ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("composes a human quotation reference, never a UUID")
    void buildsQuotationCodeFromQuoteNoAndYear() {
        var pdf = mapper.map(base().build());
        assertThat(pdf.getQuotationCode()).isEqualTo("QT-26-0045");
    }

    /**
     * The end date is not stored — it is derived from the trip length, which is. Deriving is fine;
     * inventing would not be.
     */
    @Test
    @DisplayName("derives the travel range from the start date and nights")
    void buildsTravelDateRange() {
        var pdf = mapper.map(base().build());
        assertThat(pdf.getTravelDateRange()).isEqualTo("20 Aug 2026 – 26 Aug 2026");
        assertThat(pdf.getDuration()).isEqualTo("6 Nights / 7 Days");
        assertThat(pdf.getTravellerSummary()).isEqualTo("2 Adults, 1 Child");
    }

    @Test
    @DisplayName("a party with no children says nothing about children")
    void omitsChildrenWhenThereAreNone() {
        var pdf = mapper.map(base()
                .customer(QuotationResponseDto.Customer.builder()
                        .name("Asha Nair").adults(2).children(0).infants(0).build())
                .build());
        assertThat(pdf.getTravellerSummary()).isEqualTo("2 Adults");
    }

    // ── Money ─────────────────────────────────────────────────────────────────

    /**
     * The headline case of the whole feature. Discount, GST and TCS at zero must still PRINT — a
     * customer who cannot see that nothing was added cannot check the total against the lines.
     */
    @Test
    @DisplayName("zero discount and zero GST both render as ₹0 rather than disappearing")
    void zeroAdjustmentsStayVisible() {
        var pdf = mapper.map(base().build());
        var pricing = pdf.getPricing();

        assertThat(pricing.getDiscountAmount()).isEqualTo("₹0");
        assertThat(pricing.getTaxAmount()).isEqualTo("₹0");
        assertThat(pricing.getTaxLabel()).isEqualTo("GST (0%)");
        assertThat(pricing.getGrandTotal()).isEqualTo("₹1,00,000");
    }

    @Test
    @DisplayName("prices use Indian digit grouping, not the JVM default locale")
    void formatsMoneyIndianStyle() {
        var pdf = mapper.map(base().totals(totals("1050000", "0", "18", "189000")).build());
        assertThat(pdf.getPricing().getGrandTotal()).isEqualTo("₹10,50,000");
        assertThat(pdf.getPricing().getTaxAmount()).isEqualTo("₹1,89,000");
        assertThat(pdf.getPricing().getTaxLabel()).isEqualTo("GST (18%)");
    }

    /** "per adult" is meaningless without a traveller count — ₹0 there would read as a free trip. */
    @Test
    @DisplayName("an unknown per-adult price is hidden, not shown as ₹0")
    void perAdultIsNullWhenUnknown() {
        assertThat(mapper.map(base().build()).getPricing().getPerAdult()).isNull();
    }

    @Test
    @DisplayName("the status is a label, never the enum constant")
    void statusIsHumanReadable() {
        var pricing = mapper.map(base().build()).getPricing();
        assertThat(pricing.getStatusLabel()).isEqualTo("Sent");
        assertThat(pricing.getStatusCode()).isEqualTo("SENT");
    }

    /**
     * A section line appears only when the section is included AND carries money — the same rule the
     * other three designs apply, so the money page cannot contradict the body of the document.
     */
    @Test
    @DisplayName("an excluded or zero-value section contributes no pricing row")
    void pricingRowsFollowTheInclusionRule() {
        var pdf = mapper.map(base()
                .hotel(QuotationResponseDto.HotelSection.builder()
                        .included(true).amount(new BigDecimal("60000")).hotels(List.of()).build())
                .vehicle(QuotationResponseDto.VehicleSection.builder()
                        .included(true).amount(BigDecimal.ZERO).vehicles(List.of()).build())
                .cruise(QuotationResponseDto.CruiseSection.builder()
                        .included(false).amount(new BigDecimal("30000")).cruises(List.of()).build())
                .build());

        assertThat(pdf.getPricing().getRows())
                .extracting(LuxuryQuotationPdfDto.PricingRow::getLabel)
                .containsExactly("Accommodation");
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a long itinerary is split across pages with every day kept")
    void longItineraryPaginates() {
        List<QuotationResponseDto.DayItem> days = new ArrayList<>();
        for (int i = 1; i <= 11; i++) days.add(day(i, "Attraction " + i, "Short note"));

        var pdf = mapper.map(base()
                .sightseeing(QuotationResponseDto.SightseeingSection.builder()
                        .included(true).amount(new BigDecimal("20000")).days(days).build())
                .build());

        assertThat(pdf.getItineraryPages()).hasSize(3);
        int total = pdf.getItineraryPages().stream().mapToInt(p -> p.getDays().size()).sum();
        assertThat(total).isEqualTo(11);
        assertThat(pdf.getItineraryPages().get(0).getPageNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("many hotels split three to a page with none lost")
    void manyHotelsPaginate() {
        List<QuotationResponseDto.HotelItem> hotels = new ArrayList<>();
        for (int i = 1; i <= 7; i++) hotels.add(hotel("Hotel " + i, null));

        var pdf = mapper.map(base()
                .hotel(QuotationResponseDto.HotelSection.builder()
                        .included(true).amount(new BigDecimal("60000")).hotels(hotels).build())
                .build());

        assertThat(pdf.getHotelPages()).hasSize(3);
        assertThat(pdf.getHotelPages().stream().mapToInt(p -> p.getHotels().size()).sum()).isEqualTo(7);
    }

    @Test
    @DisplayName("hotel nights are derived from the stay, not asked for")
    void computesHotelNights() {
        var pdf = mapper.map(base()
                .hotel(QuotationResponseDto.HotelSection.builder()
                        .included(true).amount(new BigDecimal("60000"))
                        .hotels(List.of(hotel("Lake View", null))).build())
                .build());

        var h = pdf.getHotelPages().get(0).getHotels().get(0);
        assertThat(h.getNightsLabel()).isEqualTo("4 Nights");
        assertThat(h.getCheckIn()).isEqualTo("20 Aug 2026");
        assertThat(h.getStarLabel()).isEqualTo("★★★★☆");
        assertThat(h.getRefundableLabel()).isEqualTo("Refundable");
    }

    /**
     * Tri-state, not a boolean. Asserting "Non-Refundable" because the agent never answered is a
     * claim the document cannot support.
     */
    @Test
    @DisplayName("an unanswered refund policy prints nothing at all")
    void unknownRefundPolicyIsHidden() {
        var withoutFlag = QuotationResponseDto.HotelItem.builder()
                .name("Lake View").checkIn("2026-08-20").checkOut("2026-08-24").build();

        var pdf = mapper.map(base()
                .hotel(QuotationResponseDto.HotelSection.builder()
                        .included(true).amount(new BigDecimal("1")).hotels(List.of(withoutFlag)).build())
                .build());

        assertThat(pdf.getHotelPages().get(0).getHotels().get(0).getRefundableLabel()).isNull();
    }

    // ── Absence ───────────────────────────────────────────────────────────────

    /**
     * The fallback URLs are always populated even when the real images are present: the template's
     * {@code onerror} needs them for a URL that exists in the database and 404s at render time.
     */
    @Test
    @DisplayName("fallback artwork is always supplied, real images or not")
    void fallbacksAreAlwaysPresent() {
        var pdf = mapper.map(base().build());
        assertThat(pdf.getCoverImageUrl()).isNull();          // this quotation has no cover
        assertThat(pdf.getFallbackCoverImageUrl()).isEqualTo("/images/pdf/fallbacks/luxury-cover.jpg");
        assertThat(pdf.getFallbackHotelImageUrl()).isEqualTo("/images/pdf/fallbacks/hotel-placeholder.jpg");
        assertThat(pdf.getFallbackAgentImageUrl()).isEqualTo("/images/pdf/fallbacks/agent-placeholder.png");
    }

    /** A quotation stripped to almost nothing must map without throwing and without inventing. */
    @Test
    @DisplayName("a near-empty quotation maps to empty sections, not to placeholder prose")
    void nullOptionalFieldsProduceEmptySections() {
        var pdf = mapper.map(QuotationResponseDto.builder()
                .publicId(UUID.randomUUID())
                .build());

        assertThat(pdf.getItineraryPages()).isEmpty();
        assertThat(pdf.getHotelPages()).isEmpty();
        assertThat(pdf.getInclusions()).isEmpty();
        assertThat(pdf.getTerms()).isEmpty();
        assertThat(pdf.getPricing()).isNull();
        assertThat(pdf.getTransport()).isNull();
        assertThat(pdf.getWelcomeMessage()).isNull();
        assertThat(pdf.getClosingMessage()).isNull();
    }

    /** No TenantContext (the public share-link path) means no agent card — never a cross-tenant read. */
    @Test
    @DisplayName("the agent block is omitted when there is no tenant in scope")
    void agentIsHiddenWithoutTenantContext() {
        assertThat(mapper.map(base().ownerUserId(7L).build()).getAgent()).isNull();
    }

    @Test
    @DisplayName("rich-text list entries are flattened and blanks dropped")
    void cleansRichTextLists() {
        var pdf = mapper.map(base()
                .inclusions(java.util.Arrays.asList("<p>Airport transfers</p>", "   ", null, "<b>Breakfast</b>"))
                .build());

        assertThat(pdf.getInclusions()).containsExactly("Airport transfers", "Breakfast");
    }

    /**
     * The structure the agent typed in the rich-text editor survives to the page. Flattening a
     * bulleted description into one paragraph was the original behaviour and lost information the
     * agent deliberately entered.
     */
    @Test
    @DisplayName("a bulleted description arrives as separate lines, not one run-on sentence")
    void richTextBulletsBecomeSeparateLines() {
        var day = QuotationResponseDto.DayItem.builder()
                .day(1).date("2026-08-20")
                .activities(List.of(QuotationResponseDto.Activity.builder()
                        .attraction("Srinagar")
                        .description("<ul><li>Shikara ride on Dal Lake</li><li>Mughal gardens</li></ul>")
                        .build()))
                .build();

        var pdf = mapper.map(base()
                .sightseeing(QuotationResponseDto.SightseeingSection.builder()
                        .included(true).amount(new BigDecimal("1")).days(List.of(day)).build())
                .build());

        assertThat(pdf.getItineraryPages().get(0).getDays().get(0).getDescriptionLines())
                .containsExactly("Shikara ride on Dal Lake", "Mughal gardens");
    }

    /**
     * Payment schedule, cancellation and terms share a sheet when they are short. Giving each its
     * own fixed-height page printed two nearly-empty A4 sheets on a typical quotation.
     */
    @Test
    @DisplayName("short policy blocks share one page")
    void shortPolicyBlocksSharePage() {
        var pdf = mapper.map(base()
                .paymentPolicies(List.of("50% on booking", "Balance 15 days before travel"))
                .cancellationPolicies(List.of("30+ days: 10%", "15-29 days: 50%"))
                .bookingTerms(List.of("Rates subject to availability", "IDs required at check-in"))
                .build());

        assertThat(pdf.getPolicyPages()).hasSize(1);
        assertThat(pdf.getPolicyPages().get(0).getBlocks())
                .extracting(LuxuryQuotationPdfDto.PolicyBlock::getTitle)
                .containsExactly("Payment Schedule", "Cancellation Policy", "Terms & Conditions");
    }

    /** …and a long list still gets the pages it needs, with nothing dropped. */
    @Test
    @DisplayName("a long terms list spills onto more pages without losing a clause")
    void longTermsSpillToMorePages() {
        List<String> terms = new ArrayList<>();
        for (int i = 1; i <= 60; i++) terms.add("Clause number " + i);

        var pdf = mapper.map(base().bookingTerms(terms).build());

        assertThat(pdf.getPolicyPages().size()).isGreaterThan(1);
        long lines = pdf.getPolicyPages().stream()
                .flatMap(p -> p.getBlocks().stream())
                .mapToLong(b -> b.getItems().size()).sum();
        assertThat(lines).isEqualTo(60);
        // Only the first part carries the plain heading; later parts say "(continued)".
        assertThat(pdf.getPolicyPages().get(1).getBlocks().get(0).isContinued()).isTrue();
    }

    @Test
    @DisplayName("snapshot tiles appear only for values that exist")
    void snapshotSkipsUnknownValues() {
        var pdf = mapper.map(base().build());
        assertThat(pdf.getSnapshotItems())
                .extracting(LuxuryQuotationPdfDto.SnapshotItem::getLabel)
                .contains("Destination", "Duration", "Travel Dates", "Travellers", "Accommodation")
                .doesNotContain("Transport");   // no vehicle section on this fixture
    }
}
