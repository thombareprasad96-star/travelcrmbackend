package com.crm.travelcrm.quotation.service;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.company.entity.Company;
import com.crm.travelcrm.company.repository.CompanyRepository;
import com.crm.travelcrm.quotation.dto.QuotationResponseDto;
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
 * Renders a quotation to a PDF byte array: Thymeleaf produces well-formed XHTML from
 * {@code templates/pdf/quotation.html}, then OpenPDF's {@link ITextRenderer}
 * (the Flying Saucer fork) lays it out as A4.
 *
 * <p>The template is parsed in {@link TemplateMode#XML} so the output is guaranteed
 * well-formed for the renderer (HTML mode would emit unclosed void tags such as
 * {@code <br>} that the XML-based renderer rejects). Company branding is taken fresh
 * from the current tenant's {@link Company} profile (resolved by {@link TenantContext}),
 * falling back to the configured {@code quotation.pdf.*} properties when a field is
 * blank or when no tenant is in scope (e.g. the public share-link path).
 */
@Service
@Slf4j
public class QuotationPdfService {

    private final TemplateEngine templateEngine;
    private final CompanyRepository companyRepository;

    private final String companyName;
    private final String companyTagline;
    private final String companyPhone;
    private final String companyEmail;
    private final String companyWebsite;
    private final String companyAddress;
    private final String companyLogoUrl;
    private final String brandColor;

    public QuotationPdfService(
            CompanyRepository companyRepository,
            @Value("${quotation.pdf.company-name:TravelCRM}") String companyName,
            @Value("${quotation.pdf.company-tagline:Your Journey, Our Passion}") String companyTagline,
            @Value("${quotation.pdf.company-phone:}") String companyPhone,
            @Value("${quotation.pdf.company-email:}") String companyEmail,
            @Value("${quotation.pdf.company-website:}") String companyWebsite,
            @Value("${quotation.pdf.company-address:}") String companyAddress,
            @Value("${quotation.pdf.company-logo-url:}") String companyLogoUrl,
            @Value("${quotation.pdf.brand-color:#2563EB}") String brandColor) {

        this.companyRepository = companyRepository;
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
        resolver.setTemplateMode(TemplateMode.XML);   // emit well-formed XHTML for the renderer
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);

        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        this.templateEngine = engine;
    }

    public byte[] render(QuotationResponseDto dto) {
        long startNanos = System.nanoTime();
        log.debug("render() start | quotation={} | title='{}'", dto.getPublicId(), dto.getTitle());

        Context ctx = new Context();
        ctx.setVariable("q", dto);
        ctx.setVariable("customer", dto.getCustomer());
        ctx.setVariable("totals", dto.getTotals());
        ctx.setVariable("fmt", new PdfFormat());
        ctx.setVariable("generatedOn", LocalDate.now());

        // Branding is taken fresh from the tenant's editable Company profile (companies table),
        // resolved by the current tenant id — never from the request/DTO. Each field falls back to
        // the configured quotation.pdf.* default when blank. On the public share-link path
        // TenantContext is null (no auth/tenant), so the configured defaults are used there.
        String cName    = companyName;
        String cLogo    = companyLogoUrl;
        String cPhone   = companyPhone;
        String cEmail   = companyEmail;
        String cWebsite = companyWebsite;
        String cAddress = companyAddress;
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
            } else {
                log.debug("No Company profile for tenant {} — using configured PDF branding defaults", tenantId);
            }
        }

        ctx.setVariable("companyName", cName);
        ctx.setVariable("companyTagline", companyTagline);   // no dedicated Company field; configured default
        ctx.setVariable("companyPhone", cPhone);
        ctx.setVariable("companyEmail", cEmail);
        ctx.setVariable("companyWebsite", cWebsite);
        ctx.setVariable("companyAddress", cAddress);
        ctx.setVariable("companyLogoUrl", cLogo);
        ctx.setVariable("brandColor", brandColor);
        ctx.setVariable("companyGst", cGst);
        ctx.setVariable("companyGoogleReviews", cReviews);
        ctx.setVariable("companyYearsExperience", cYears);

        String html = templateEngine.process("pdf/quotation", ctx);
        log.debug("Thymeleaf produced XHTML for {} ({} chars); laying out PDF...",
                dto.getPublicId(), html.length());

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            renderer.setDocumentFromString(html);
            renderer.layout();
            renderer.createPDF(out);
            byte[] pdf = out.toByteArray();
            log.debug("PDF generated for {} | {} bytes in {} ms",
                    dto.getPublicId(), pdf.length, (System.nanoTime() - startNanos) / 1_000_000);
            return pdf;
        } catch (Exception ex) {
            log.error("Failed to render quotation PDF for {}: {}",
                    dto.getPublicId(), ex.getMessage(), ex);
            throw new BusinessException("Failed to generate quotation PDF: " + ex.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}