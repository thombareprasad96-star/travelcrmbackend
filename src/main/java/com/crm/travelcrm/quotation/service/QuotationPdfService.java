package com.crm.travelcrm.quotation.service;

import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.quotation.dto.QuotationResponseDto;
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
 * Renders a quotation to a PDF byte array: Thymeleaf produces well-formed XHTML from
 * {@code templates/pdf/quotation.html}, then OpenPDF's {@link ITextRenderer}
 * (the Flying Saucer fork) lays it out as A4.
 *
 * <p>The template is parsed in {@link TemplateMode#XML} so the output is guaranteed
 * well-formed for the renderer (HTML mode would emit unclosed void tags such as
 * {@code <br>} that the XML-based renderer rejects). Company branding is configurable
 * via {@code quotation.pdf.*} properties.
 */
@Service
@Slf4j
public class QuotationPdfService {

    private final TemplateEngine templateEngine;

    private final String companyName;
    private final String companyTagline;
    private final String companyPhone;
    private final String companyEmail;
    private final String companyWebsite;
    private final String companyAddress;
    private final String companyLogoUrl;
    private final String brandColor;

    public QuotationPdfService(
            @Value("${quotation.pdf.company-name:TravelCRM}") String companyName,
            @Value("${quotation.pdf.company-tagline:Your Journey, Our Passion}") String companyTagline,
            @Value("${quotation.pdf.company-phone:}") String companyPhone,
            @Value("${quotation.pdf.company-email:}") String companyEmail,
            @Value("${quotation.pdf.company-website:}") String companyWebsite,
            @Value("${quotation.pdf.company-address:}") String companyAddress,
            @Value("${quotation.pdf.company-logo-url:}") String companyLogoUrl,
            @Value("${quotation.pdf.brand-color:#2563EB}") String brandColor) {

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
        Context ctx = new Context();
        ctx.setVariable("q", dto);
        ctx.setVariable("customer", dto.getCustomer());
        ctx.setVariable("totals", dto.getTotals());
        ctx.setVariable("fmt", new PdfFormat());
        ctx.setVariable("generatedOn", LocalDate.now());

        ctx.setVariable("companyName", companyName);
        ctx.setVariable("companyTagline", companyTagline);
        ctx.setVariable("companyPhone", companyPhone);
        ctx.setVariable("companyEmail", companyEmail);
        ctx.setVariable("companyWebsite", companyWebsite);
        ctx.setVariable("companyAddress", companyAddress);
        ctx.setVariable("companyLogoUrl", companyLogoUrl);
        ctx.setVariable("brandColor", brandColor);

        String html = templateEngine.process("pdf/quotation", ctx);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            renderer.setDocumentFromString(html);
            renderer.layout();
            renderer.createPDF(out);
            return out.toByteArray();
        } catch (Exception ex) {
            log.error("Failed to render quotation PDF for {}: {}",
                    dto.getPublicId(), ex.getMessage(), ex);
            throw new BusinessException("Failed to generate quotation PDF: " + ex.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}