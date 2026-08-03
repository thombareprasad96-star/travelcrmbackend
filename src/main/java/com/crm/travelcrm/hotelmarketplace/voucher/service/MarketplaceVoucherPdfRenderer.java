package com.crm.travelcrm.hotelmarketplace.voucher.service;

import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.hotelmarketplace.booking.entity.PlatformHotelBooking;
import com.crm.travelcrm.hotelmarketplace.booking.enums.VoucherStatus;
import com.crm.travelcrm.hotelmarketplace.voucher.dto.MarketplaceVoucherModel;
import com.crm.travelcrm.quotation.service.PdfFormat;
import lombok.extern.slf4j.Slf4j;
import org.openpdf.pdf.ITextRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;

/**
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *  THE GUEST HOLDS THIS DOCUMENT. IT CARRIES NO MONEY. NONE.
 *
 *  Design §7: "Voucher must not expose supplier net amount, platform commission, internal notes or
 *  contract data." That prohibition is wider than it first reads — {@code tenantPayable} and
 *  {@code tenantCustomerSellingAmount} are equally forbidden here, because printing what the tenant
 *  paid the platform, or what the tenant charged their own customer, on a page the customer carries
 *  to a hotel desk discloses the tenant's margin to their own client and the platform's to both.
 *
 *  Do not add a "total", a "balance due" or a "paid" line to this renderer or its template. If a
 *  priced document is ever needed, it is a different document with a different audience — the CRM
 *  invoice at {@code /api/bookings/{id}/invoice} already exists for that.
 *
 *  The template is fed {@link MarketplaceVoucherModel}, which has no money field at all, so the
 *  guarantee survives whoever edits the HTML next.
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * <p>Rendering follows {@code BookingPdfService} exactly: Thymeleaf emits well-formed XHTML (parsed
 * in {@link TemplateMode#XML}), OpenPDF's {@link ITextRenderer} lays it out as A4, and the bytes are
 * returned in memory. <b>Nothing is written to disk or to Cloudinary</b> — Cloudinary URLs are public
 * and unauthenticated, and this page names a guest, their phone number and where they are sleeping.</p>
 */
@Slf4j
@Service
public class MarketplaceVoucherPdfRenderer {

    private static final String TEMPLATE = "pdf/marketplace-hotel-voucher";

    private final TemplateEngine templateEngine;

    /**
     * Platform identity, not tenant identity.
     *
     * <p>The tenant's own {@code Company} profile is deliberately NOT consulted. A voucher number is a
     * document identity: the same number must not name two different issuers depending on who
     * downloaded it, and the party the hotel actually confirmed the room to is the platform, which is
     * also the support desk the guest needs at 11pm when the front desk cannot find the booking
     * (design §7 — "platform support contact").</p>
     */
    private final String platformName;
    private final String platformTagline;
    private final String supportPhone;
    private final String supportEmail;
    private final String website;
    private final String logoUrl;

    public MarketplaceVoucherPdfRenderer(
            @Value("${marketplace.voucher.platform-name:${quotation.pdf.company-name:TravelCRM}}") String platformName,
            @Value("${marketplace.voucher.platform-tagline:Hotel Reservation Confirmation}") String platformTagline,
            @Value("${marketplace.voucher.support-phone:${quotation.pdf.company-phone:}}") String supportPhone,
            @Value("${marketplace.voucher.support-email:${quotation.pdf.company-email:}}") String supportEmail,
            @Value("${marketplace.voucher.website:${quotation.pdf.company-website:}}") String website,
            @Value("${marketplace.voucher.logo-url:${quotation.pdf.company-logo-url:}}") String logoUrl) {

        this.platformName = platformName;
        this.platformTagline = platformTagline;
        this.supportPhone = supportPhone;
        this.supportEmail = supportEmail;
        this.website = website;
        this.logoUrl = logoUrl;

        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.XML);   // well-formed XHTML for the renderer
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);

        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        this.templateEngine = engine;
    }

    public byte[] render(PlatformHotelBooking booking) {
        long startNanos = System.nanoTime();

        Context ctx = new Context();
        ctx.setVariable("doc", toModel(booking));
        ctx.setVariable("fmt", new PdfFormat());

        String html = templateEngine.process(TEMPLATE, ctx);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            renderer.setDocumentFromString(html);
            renderer.layout();
            renderer.createPDF(out);
            byte[] pdf = out.toByteArray();
            log.debug("Rendered marketplace voucher for {} | {} bytes in {} ms",
                    booking.getBookingCode(), pdf.length, (System.nanoTime() - startNanos) / 1_000_000);
            return pdf;
        } catch (Exception ex) {
            // The renderer cause (iText/font/resource internals) stays in the log and must NOT be
            // echoed to the client, which surfaces BusinessException messages verbatim.
            log.error("Failed to render marketplace voucher for {}: {}",
                    booking.getBookingCode(), ex.getMessage(), ex);
            throw new BusinessException("We couldn't generate the voucher. Please try again.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * The whitelist. Every field the voucher shows is named here explicitly; anything not listed
     * cannot reach the page, which is why this is a copy rather than a pass-through of the entity.
     */
    private MarketplaceVoucherModel toModel(PlatformHotelBooking b) {
        VoucherStatus voucherStatus = b.getVoucherStatus() == null
                ? VoucherStatus.NOT_ISSUED : b.getVoucherStatus();

        return MarketplaceVoucherModel.builder()
                .bookingCode(b.getBookingCode())
                .voucherNumber(b.getVoucherNumber())
                .supplierConfirmationNumber(b.getSupplierConfirmationNumber())
                .issuedOn(b.getVoucherIssuedAt() != null ? b.getVoucherIssuedAt().toLocalDate() : LocalDate.now())
                .issued(voucherStatus.isDownloadable())
                .statusLabel(label(voucherStatus))
                .hotelName(b.getHotelNameSnapshot())
                .address(b.getAddressSnapshot())
                .cityName(b.getCityNameSnapshot())
                .countryCode(b.getCountryCodeSnapshot())
                .roomName(b.getRoomNameSnapshot())
                .mealPlan(b.getMealPlanSnapshot())
                .checkIn(b.getCheckIn())
                .checkOut(b.getCheckOut())
                .nights(b.getNights())
                .rooms(b.getRooms())
                .adults(b.getAdults())
                .children(b.getChildren())
                .leadGuestName(b.getLeadGuestName())
                .leadGuestPhone(b.getLeadGuestPhone())
                .leadGuestEmail(b.getLeadGuestEmail())
                .specialRequests(b.getSpecialRequests())
                .cancellationTerms(b.getCancellationTermsSnapshot())
                .platformName(platformName)
                .platformTagline(platformTagline)
                .platformSupportPhone(supportPhone)
                .platformSupportEmail(supportEmail)
                .platformWebsite(website)
                .platformLogoUrl(logoUrl)
                .build();
    }

    private static String label(VoucherStatus status) {
        return switch (status) {
            case ISSUED -> "Confirmed";
            case REVOKED -> "Revoked";
            case NOT_ISSUED -> "Preview - Not Issued";
        };
    }
}
