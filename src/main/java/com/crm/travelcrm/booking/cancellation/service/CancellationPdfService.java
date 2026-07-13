package com.crm.travelcrm.booking.cancellation.service;

import com.crm.travelcrm.booking.cancellation.dto.CancellationDocumentModel;
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
 * Renders a cancellation document (credit/debit note or refund voucher) to PDF bytes, mirroring
 * {@code BookingPdfService}: Thymeleaf → well-formed XHTML → OpenPDF {@link ITextRenderer}. Unlike the
 * booking invoice, branding is read from the FROZEN {@link CancellationDocumentModel} (not live
 * company data), so a reissue reproduces the original document exactly — a statutory requirement for a
 * numbered credit note.
 */
@Service
@Slf4j
public class CancellationPdfService {

    private final TemplateEngine templateEngine;

    public CancellationPdfService() {
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

    public byte[] renderNote(CancellationDocumentModel model) {
        return render(model, "pdf/cancellation-note");
    }

    public byte[] renderRefundVoucher(CancellationDocumentModel model) {
        return render(model, "pdf/refund-voucher");
    }

    private byte[] render(CancellationDocumentModel model, String template) {
        Context ctx = new Context();
        ctx.setVariable("doc", model);
        ctx.setVariable("fmt", new PdfFormat());

        String html = templateEngine.process(template, ctx);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();
            renderer.setDocumentFromString(html);
            renderer.layout();
            renderer.createPDF(out);
            return out.toByteArray();
        } catch (Exception ex) {
            log.error("Failed to render {} ({}): {}", template, model.getDocNumber(), ex.getMessage(), ex);
            throw new BusinessException("We couldn't generate the document. Please try again.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
