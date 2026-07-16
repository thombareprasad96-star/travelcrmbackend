package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.booking.dto.pdf.BookingDocumentModel;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.company.entity.Company;
import com.crm.travelcrm.company.repository.CompanyRepository;
import com.crm.travelcrm.quotation.service.PdfFormat;
import com.crm.travelcrm.subagent.service.SubAgentBrandingService;
import lombok.extern.slf4j.Slf4j;
import org.openpdf.pdf.ITextRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.Year;

/**
 * Renders a booking Invoice or Voucher to a PDF byte array, mirroring
 * {@link com.crm.travelcrm.quotation.service.QuotationPdfService}: Thymeleaf emits well-formed
 * XHTML (parsed in {@link TemplateMode#XML}), then OpenPDF's {@link ITextRenderer} (the Flying
 * Saucer fork) lays it out as A4.
 *
 * <p>Company branding is taken fresh from the current tenant's editable {@link Company} profile,
 * falling back to the configured {@code quotation.pdf.*} defaults so both document families share
 * one brand. Documents are rendered on-the-fly (no Cloudinary caching) so an invoice always
 * reflects the booking's current paid/pending figures.</p>
 */
@Service
@Slf4j
public class BookingPdfService {

    private final TemplateEngine templateEngine;
    private final CompanyRepository companyRepository;
    private final SubAgentBrandingService brandingService;

    private final String companyName;
    private final String companyTagline;
    private final String companyPhone;
    private final String companyEmail;
    private final String companyWebsite;
    private final String companyAddress;
    private final String companyLogoUrl;
    private final String brandColor;

    public BookingPdfService(
            CompanyRepository companyRepository,
            SubAgentBrandingService brandingService,
            @Value("${quotation.pdf.company-name:TravelCRM}") String companyName,
            @Value("${quotation.pdf.company-tagline:Your Journey, Our Passion}") String companyTagline,
            @Value("${quotation.pdf.company-phone:}") String companyPhone,
            @Value("${quotation.pdf.company-email:}") String companyEmail,
            @Value("${quotation.pdf.company-website:}") String companyWebsite,
            @Value("${quotation.pdf.company-address:}") String companyAddress,
            @Value("${quotation.pdf.company-logo-url:}") String companyLogoUrl,
            @Value("${booking.pdf.brand-color:#B8891F}") String brandColor) {

        this.companyRepository = companyRepository;
        this.brandingService = brandingService;
        this.companyName = companyName;
        this.companyTagline = companyTagline;
        this.companyPhone = companyPhone;
        this.companyEmail = companyEmail;
        this.companyWebsite = companyWebsite;
        this.companyAddress = companyAddress;
        this.companyLogoUrl = companyLogoUrl;
        this.brandColor = brandColor;

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

    /** Booking tax invoice (GST/TCS breakdown + payment ledger). */
    public byte[] renderInvoice(BookingDocumentModel model) {
        return render(model, "pdf/invoice");
    }

    /** Booking confirmation voucher (services + travel details, no financial internals). */
    public byte[] renderVoucher(BookingDocumentModel model) {
        return render(model, "pdf/voucher");
    }

    private byte[] render(BookingDocumentModel doc, String template) {
        long startNanos = System.nanoTime();
        Context ctx = new Context();
        ctx.setVariable("doc", doc);
        ctx.setVariable("fmt", new PdfFormat());
        ctx.setVariable("generatedOn", doc.getGeneratedOn() != null ? doc.getGeneratedOn() : LocalDate.now());

        applyBranding(ctx, doc.getOwnerUserId());

        String html = templateEngine.process(template, ctx);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            renderer.setDocumentFromString(html);
            renderer.layout();
            renderer.createPDF(out);
            byte[] pdf = out.toByteArray();
            log.debug("Rendered {} for {} | {} bytes in {} ms",
                    template, doc.getBookingCode(), pdf.length, (System.nanoTime() - startNanos) / 1_000_000);
            return pdf;
        } catch (Exception ex) {
            // The renderer cause (iText/font/resource internals) stays in the log and must NOT be
            // echoed to the client, which surfaces BusinessException messages verbatim.
            log.error("Failed to render {} for booking {}: {}",
                    template, doc.getBookingCode(), ex.getMessage(), ex);
            throw new BusinessException("We couldn't generate the document. Please try again.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Resolve branding fresh from the tenant Company profile, falling back to configured defaults;
     * then, when {@code ownerUserId} is an active sub-agent, white-label the identity fields with
     * their brand (legal details — address/website/GST/reviews — stay the parent's).
     */
    private void applyBranding(Context ctx, Long ownerUserId) {
        String cName    = companyName;
        String cLogo    = companyLogoUrl;
        String cPhone   = companyPhone;
        String cEmail   = companyEmail;
        String cWebsite = companyWebsite;
        String cAddress = companyAddress;
        String cColor   = brandColor;
        String cGst     = null;
        Integer cReviews = null;
        Integer cYears   = null;

        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            Company co = companyRepository.findByTenantId(tenantId).orElse(null);
            if (co != null) {
                if (StringUtils.hasText(co.getName()))    cName    = co.getName();
                if (StringUtils.hasText(co.getLogoUrl())) cLogo    = co.getLogoUrl();
                if (StringUtils.hasText(co.getPhone()))   cPhone   = co.getPhone();
                if (StringUtils.hasText(co.getEmail()))   cEmail   = co.getEmail();
                if (StringUtils.hasText(co.getWebsite())) cWebsite = co.getWebsite();
                if (StringUtils.hasText(co.getAddress())) cAddress = co.getAddress();
                cGst     = co.getGstin();
                cReviews = co.getTotalReviews();
                if (co.getOperatingSince() != null) {
                    cYears = Year.now().getValue() - co.getOperatingSince();
                }
            }
        }

        var branding = brandingService.resolve(ownerUserId);
        if (branding.isPresent()) {
            var b = branding.get();
            if (StringUtils.hasText(b.brandName()))    cName  = b.brandName();
            if (StringUtils.hasText(b.logoUrl()))      cLogo  = b.logoUrl();
            if (StringUtils.hasText(b.contactPhone())) cPhone = b.contactPhone();
            if (StringUtils.hasText(b.contactEmail())) cEmail = b.contactEmail();
            if (StringUtils.hasText(b.brandColor()))   cColor = b.brandColor();
        }

        ctx.setVariable("companyName", cName);
        ctx.setVariable("companyTagline", companyTagline);
        ctx.setVariable("companyPhone", cPhone);
        ctx.setVariable("companyEmail", cEmail);
        ctx.setVariable("companyWebsite", cWebsite);
        ctx.setVariable("companyAddress", cAddress);
        ctx.setVariable("companyLogoUrl", cLogo);
        ctx.setVariable("brandColor", cColor);
        ctx.setVariable("companyGst", cGst);
        ctx.setVariable("companyGoogleReviews", cReviews);
        ctx.setVariable("companyYearsExperience", cYears);
    }
}