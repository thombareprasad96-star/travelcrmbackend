package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.company.entity.Company;
import com.crm.travelcrm.company.repository.CompanyRepository;
import com.crm.travelcrm.fleet.dto.FleetDutySlipModel;
import com.crm.travelcrm.fleet.dto.FleetSettlementSheetModel;
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

/**
 * Renders the two documents fleet operations actually run on: the <b>duty slip</b> that rides with
 * the vehicle, and the <b>settlement sheet</b> the driver signs.
 *
 * <p>Same pipeline as the booking and quotation documents — Thymeleaf emits well-formed XHTML
 * (parsed in {@link TemplateMode#XML}), OpenPDF's {@link ITextRenderer} lays it out as A4 — and, as
 * there, the resolver is built locally rather than shared. That duplication is the established
 * house shape across four PDF services already, and here it is also what keeps fleet importable
 * into a standalone build: {@code quotation} is a CRM module a Fleet-only deployment does not ship.
 *
 * <p>Branding comes from the tenant's own {@link Company} profile, falling back to the configured
 * {@code quotation.pdf.*} defaults so every document in the product carries one identity.
 * Deliberately no sub-agent white-labelling: a duty slip is an internal operations document and a
 * settlement sheet is a cash receipt between the operator and his own driver — neither is a
 * customer-facing artefact a travel partner rebrands.
 *
 * <p>Rendered on the fly, never cached to Cloudinary: a slip printed today must show today's
 * readings, and a settlement sheet is a financial document that must never sit behind a public URL.
 */
@Service
@Slf4j
public class FleetPdfService {

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

    public FleetPdfService(
            CompanyRepository companyRepository,
            @Value("${quotation.pdf.company-name:TravelCRM}") String companyName,
            @Value("${quotation.pdf.company-tagline:Your Journey, Our Passion}") String companyTagline,
            @Value("${quotation.pdf.company-phone:}") String companyPhone,
            @Value("${quotation.pdf.company-email:}") String companyEmail,
            @Value("${quotation.pdf.company-website:}") String companyWebsite,
            @Value("${quotation.pdf.company-address:}") String companyAddress,
            @Value("${quotation.pdf.company-logo-url:}") String companyLogoUrl,
            @Value("${fleet.pdf.brand-color:#1d4ed8}") String brandColor) {

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
        resolver.setTemplateMode(TemplateMode.XML);   // well-formed XHTML for the renderer
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);

        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        this.templateEngine = engine;
    }

    /** The paper that rides with the vehicle. Printable from PLANNED onward, blanks and all. */
    public byte[] renderDutySlip(FleetDutySlipModel model) {
        Context ctx = new Context();
        ctx.setVariable("doc", model);
        ctx.setVariable("generatedOn", model.getGeneratedOn() != null ? model.getGeneratedOn() : LocalDate.now());
        return render(ctx, "pdf/fleet-duty-slip", model.getSlipNo());
    }

    /** The driver's hisaab, for signing. */
    public byte[] renderSettlementSheet(FleetSettlementSheetModel model) {
        Context ctx = new Context();
        ctx.setVariable("doc", model);
        ctx.setVariable("generatedOn", model.getGeneratedOn() != null ? model.getGeneratedOn() : LocalDate.now());
        return render(ctx, "pdf/fleet-settlement-sheet", model.getSheetNo());
    }

    private byte[] render(Context ctx, String template, String reference) {
        long startNanos = System.nanoTime();
        ctx.setVariable("fmt", new FleetPdfFormat());
        applyBranding(ctx);

        String html = templateEngine.process(template, ctx);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            renderer.setDocumentFromString(html);
            renderer.layout();
            renderer.createPDF(out);
            byte[] pdf = out.toByteArray();
            log.debug("Rendered {} for {} | {} bytes in {} ms",
                    template, reference, pdf.length, (System.nanoTime() - startNanos) / 1_000_000);
            return pdf;
        } catch (Exception ex) {
            // The renderer cause (iText/font/resource internals) stays in the log and must NOT be
            // echoed to the client, which surfaces BusinessException messages verbatim.
            log.error("Failed to render {} for {}: {}", template, reference, ex.getMessage(), ex);
            throw new BusinessException("We couldn't generate the document. Please try again.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void applyBranding(Context ctx) {
        String cName    = companyName;
        String cLogo    = companyLogoUrl;
        String cPhone   = companyPhone;
        String cEmail   = companyEmail;
        String cWebsite = companyWebsite;
        String cAddress = companyAddress;
        String cGst     = null;

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
                cGst = co.getGstin();
            }
        }

        ctx.setVariable("companyName", cName);
        ctx.setVariable("companyTagline", companyTagline);
        ctx.setVariable("companyPhone", cPhone);
        ctx.setVariable("companyEmail", cEmail);
        ctx.setVariable("companyWebsite", cWebsite);
        ctx.setVariable("companyAddress", cAddress);
        ctx.setVariable("companyLogoUrl", cLogo);
        ctx.setVariable("companyGst", cGst);
        ctx.setVariable("brandColor", brandColor);
    }
}
