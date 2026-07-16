package com.crm.travelcrm.accounting.invoice.service;

import com.crm.travelcrm.accounting.invoice.dto.pdf.TaxInvoiceModel;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.quotation.service.PdfFormat;
import lombok.extern.slf4j.Slf4j;
import org.openpdf.pdf.ITextRenderer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.ByteArrayOutputStream;

/**
 * Renders a GST invoice / bill of supply to PDF bytes, mirroring {@code CancellationPdfService}:
 * Thymeleaf → well-formed XHTML → OpenPDF {@link ITextRenderer}. Branding, GSTIN and every figure are
 * read from the FROZEN {@link TaxInvoiceModel} (not live data), so a reprint reproduces the original
 * numbered invoice exactly — a statutory requirement.
 */
@Service
@Slf4j
public class InvoicePdfService {

    private final TemplateEngine templateEngine;

    public InvoicePdfService() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.XML);   // well-formed XHTML for Flying Saucer
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);

        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        this.templateEngine = engine;
    }

    public byte[] renderInvoice(TaxInvoiceModel model) {
        Context ctx = new Context();
        ctx.setVariable("doc", model);
        ctx.setVariable("fmt", new PdfFormat());

        String html = templateEngine.process("pdf/gst-invoice", ctx);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            renderer.setDocumentFromString(html);
            renderer.layout();
            renderer.createPDF(out);
            return out.toByteArray();
        } catch (Exception ex) {
            log.error("Failed to render GST invoice {}: {}", model.getInvoiceNumber(), ex.getMessage(), ex);
            throw new BusinessException("We couldn't generate the invoice. Please try again.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}