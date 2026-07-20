package com.crm.travelcrm.quotation.service;

import com.crm.travelcrm.company.repository.CompanyRepository;
import com.crm.travelcrm.quotation.dto.QuotationResponseDto;
import com.crm.travelcrm.quotation.enums.TemplateStyle;
import com.crm.travelcrm.subagent.service.SubAgentBrandingService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Renders REAL PDFs to {@code target/pdf-preview/} so a human can open and judge them.
 *
 * <p>This is deliberately more than a unit test: OpenPDF fails an entire render on a single
 * malformed-XML tag or an unresolvable binding, and no assertion can judge whether a design looks
 * premium — only eyes can. So the test's real product is the files it writes; the assertions only
 * guarantee the render did not blow up and produced a plausible document.
 *
 * <p>No Spring context, no database: the service is constructed by hand with mocks (empty Company →
 * configured branding defaults, no sub-agent white-label), exactly the state the PUBLIC share-link
 * path runs in where {@code TenantContext} is null.
 *
 * <p>The MODERN case skips (rather than fails) while {@code templates/pdf/quotation-modern.html}
 * does not exist yet, so this test is safe to run mid-build-out.
 */
class QuotationPdfRenderSmokeTest {

    private static QuotationPdfService service;
    private static final Path OUT_DIR = Path.of("target", "pdf-preview");

    /**
     * Write the preview, surviving Windows file locks.
     *
     * <p>These PDFs exist to be OPENED — and a PDF open in a viewer is write-locked on Windows, which
     * failed this test with a FileSystemException AFTER the render had already passed its assertions.
     * The render verdict must not depend on whether a human currently has the preview open, and the
     * fix must never be "kill the viewer" (that has been done to this machine once already). Locked ⇒
     * the fresh bytes land beside the stale file as *.new.pdf, and the console says so.
     */
    private static void writePreview(String fileName, byte[] pdf) throws Exception {
        Path target = OUT_DIR.resolve(fileName);
        try {
            Files.write(target, pdf);
        } catch (java.nio.file.FileSystemException locked) {
            Path fallback = OUT_DIR.resolve(fileName.replace(".pdf", ".new.pdf"));
            Files.write(fallback, pdf);
            System.out.println("[pdf-preview] " + fileName + " is open in a viewer (locked) — fresh "
                    + "render written to " + fallback.getFileName() + " instead. Close the viewer and "
                    + "re-run to refresh the original.");
        }
    }

    @BeforeAll
    static void buildService() throws Exception {
        CompanyRepository companyRepository = mock(CompanyRepository.class);
        when(companyRepository.findByTenantId(any())).thenReturn(Optional.empty());
        SubAgentBrandingService branding = mock(SubAgentBrandingService.class);
        when(branding.resolve(any())).thenReturn(Optional.empty());

        service = new QuotationPdfService(
                companyRepository, branding,
                "MyTripSafar", "Your Journey, Our Passion",
                "+91 98765 43210", "hello@mytripsafar.com",
                "www.mytripsafar.com", "Gorakhpur, Uttar Pradesh",
                "",              // no logo URL — exercises the fallback branch
                "#2563EB");

        Files.createDirectories(OUT_DIR);
    }

    @Test
    @DisplayName("CLASSIC renders end-to-end and lands in target/pdf-preview/quotation-classic.pdf")
    void classicRenders() throws Exception {
        byte[] pdf = service.render(sampleQuotation(TemplateStyle.CLASSIC));

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");   // a real PDF header, not an error page
        assertThat(pdf.length).isGreaterThan(10_000);            // a styled multi-section doc, not a stub

        writePreview("quotation-classic.pdf", pdf);
    }

    @Test
    @DisplayName("PREMIUM renders end-to-end once its template exists (skips until then)")
    void premiumRenders() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                new ClassPathResource("templates/pdf/quotation-premium.html").exists(),
                "quotation-premium.html not authored yet — skipping, not failing");

        byte[] pdf = service.render(sampleQuotation(TemplateStyle.PREMIUM));

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(10_000);

        writePreview("quotation-premium.pdf", pdf);
    }

    @Test
    @DisplayName("MODERN renders end-to-end once its template exists (skips until then)")
    void modernRenders() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                new ClassPathResource("templates/pdf/quotation-modern.html").exists(),
                "quotation-modern.html not authored yet — skipping, not failing");

        byte[] pdf = service.render(sampleQuotation(TemplateStyle.MODERN));

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(10_000);

        writePreview("quotation-modern.pdf", pdf);
    }

    // ── the sample trip: Gorakhpur → Nepal, 4 pax, 3 days — realistic, image included ──

    /**
     * The cover image is the locally-bundled {@code templates/pdf/travel.png}, addressed by a
     * CORRECT file URI for THIS checkout. That is deliberate twice over: it proves the renderer's
     * image pipeline deterministically (no network flake in a test), and it stands in contrast to
     * the Classic template's own hardcoded {@code file:///C:/travelcrmbackend/...} paths — which do
     * not exist on this machine or in the deployed jar, so Classic's built-in cover/banner art will
     * be visibly MISSING from the output. That absence in the sample PDF is the live bug on display,
     * not a defect in this test.
     */
    private static String localCoverImage() {
        return new File("src/main/resources/templates/pdf/travel.png").toURI().toString();
    }

    /**
     * Test-only sample photos (src/test/resources/pdf-sample-images/, never packaged into the prod
     * jar) as file URIs. Local on purpose: hotlinked images make the render — and therefore this
     * test — hostage to someone else's CDN being up and unthrottled. The Cloudinary-transform
     * replace in the templates no-ops on these (no '/upload/' segment), which is itself the
     * documented behaviour for a non-Cloudinary URL.
     */
    private static String samplePhoto(String name) {
        return new File("src/test/resources/pdf-sample-images/" + name + ".jpg").toURI().toString();
    }

    private static QuotationResponseDto sampleQuotation(TemplateStyle style) {
        return QuotationResponseDto.builder()
                .publicId(UUID.randomUUID())
                .title("Nepal Family Getaway — Pokhara & Kathmandu")
                .version("v1.0")
                .versionNumber(1)
                .quoteNo(1042)
                .nights(2).days(3).rooms(2)
                .templateStyle(style)
                .coverImageUrl(localCoverImage())
                .customer(QuotationResponseDto.Customer.builder()
                        .name("Asha Rao").phone("+91 98765 43210").email("asha@example.com")
                        .destination("Pokhara & Kathmandu, Nepal")
                        .travelDate(LocalDate.now().plusDays(30))
                        .adults(2).children(2).infants(0)
                        .build())
                .flight(QuotationResponseDto.FlightSection.builder()
                        .included(true).title("Flights").amount(new BigDecimal("48000"))
                        .journey("Gorakhpur → Kathmandu → Gorakhpur")
                        .segments(List.of(QuotationResponseDto.Segment.builder()
                                .airline("Buddha Air").flightNo("U4-608")
                                .from("GOP").to("KTM")
                                .depDate("2026-08-15").depTime("10:20")
                                .arrDate("2026-08-15").arrTime("11:35")
                                .duration("1h 15m").cabin(7).checkin(20)
                                .pricePerPax(new BigDecimal("12000")).pax(4)
                                .build()))
                        .build())
                .hotel(QuotationResponseDto.HotelSection.builder()
                        .included(true).title("Hotels").amount(new BigDecimal("36000"))
                        .hotels(List.of(
                                QuotationResponseDto.HotelItem.builder()
                                        .name("Temple Tree Resort & Spa").city("Pokhara")
                                        .checkIn("2026-08-15").checkOut("2026-08-17")
                                        .roomType("Deluxe Garden View").mealPlan("Breakfast")
                                        .refundable(true).stars(4)
                                        .pricePerRoom(new BigDecimal("9000")).rooms(2)
                                        .imagePath(samplePhoto("hotel"))
                                        .build()))
                        .build())
                .sightseeing(QuotationResponseDto.SightseeingSection.builder()
                        .included(true).title("Sightseeing").amount(new BigDecimal("14000"))
                        .days(List.of(
                                QuotationResponseDto.DayItem.builder()
                                        .day(1).date("2026-08-15")
                                        .activities(List.of(QuotationResponseDto.Activity.builder()
                                                .attraction("Phewa Lake boating & Tal Barahi Temple")
                                                .startTime("15:00")
                                                .description("Sunset boat ride across Phewa Lake with "
                                                        + "a stop at the island temple.")
                                                .meals(List.of("Dinner"))
                                                .transfer("Private car")
                                                .imagePath(samplePhoto("lake"))
                                                .build()))
                                        .build(),
                                QuotationResponseDto.DayItem.builder()
                                        .day(2).date("2026-08-16")
                                        .activities(List.of(QuotationResponseDto.Activity.builder()
                                                .attraction("Sarangkot sunrise & Davis Falls")
                                                .startTime("04:30")
                                                .description("Annapurna range sunrise from Sarangkot, "
                                                        + "then Davis Falls and Gupteshwor Cave.")
                                                .meals(List.of("Breakfast", "Lunch"))
                                                .transfer("Private car")
                                                .imagePath(samplePhoto("sunrise"))
                                                .build()))
                                        .build(),
                                QuotationResponseDto.DayItem.builder()
                                        .day(3).date("2026-08-17")
                                        .activities(List.of(QuotationResponseDto.Activity.builder()
                                                .attraction("Kathmandu — Pashupatinath & Swayambhunath")
                                                .startTime("09:00")
                                                .description("Morning darshan, then the monkey temple "
                                                        + "stupa before the evening flight home.")
                                                .meals(List.of("Breakfast"))
                                                .transfer("Private car")
                                                .imagePath(samplePhoto("temple"))
                                                .build()))
                                        .build()))
                        .build())
                .inclusions(List.of(
                        "Return flights Gorakhpur–Kathmandu for 4 travellers",
                        "2 nights at Temple Tree Resort & Spa with breakfast",
                        "All transfers and sightseeing by private car",
                        "English-speaking guide on all sightseeing days"))
                .exclusions(List.of(
                        "Lunches and dinners not listed in the itinerary",
                        "Travel insurance",
                        "Anything not expressly mentioned under inclusions"))
                .paymentPolicies(List.of(
                        "50% advance to confirm the booking",
                        "Balance 7 days before departure"))
                .cancellationPolicies(List.of(
                        "Free cancellation until 15 days before departure",
                        "50% charge within 7 days of departure"))
                .bookingTerms(List.of("Rates subject to availability at the time of confirmation"))
                .totals(QuotationResponseDto.Totals.builder()
                        .subtotal(new BigDecimal("98000"))
                        .discountAmount(new BigDecimal("5000"))
                        .taxPercent(new BigDecimal("5"))
                        .taxAmount(new BigDecimal("4650"))
                        .grandTotal(new BigDecimal("97650"))
                        .perAdult(new BigDecimal("48825"))
                        .build())
                .createdAt(java.time.LocalDateTime.now())
                .build();
    }
}
