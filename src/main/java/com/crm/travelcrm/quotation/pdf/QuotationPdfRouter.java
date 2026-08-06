package com.crm.travelcrm.quotation.pdf;

import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.quotation.dto.QuotationResponseDto;
import com.crm.travelcrm.quotation.enums.TemplateStyle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The single entry point every PDF download goes through: picks the renderer that owns the design.
 *
 * <p>Routing is by {@link QuotationPdfRenderer#supports}, not a switch, so the set of designs lives
 * in one place — the enum — and adding one means adding a bean. The ordering guarantee that matters
 * is the negative one: a style with no renderer is an error, never a quiet substitution.
 *
 * <p><b>Nothing here can affect CLASSIC/MODERN/PREMIUM.</b> They resolve to
 * {@link LegacyQuotationPdfRenderer}, which is a straight delegation to the unmodified
 * {@code QuotationPdfService}. A Chromium fault, a missing browser or {@code pdf.luxury.enabled=false}
 * are all confined to the Luxury branch.
 */
@Service
@Slf4j
public class QuotationPdfRouter {

    private final List<QuotationPdfRenderer> renderers;

    public QuotationPdfRouter(List<QuotationPdfRenderer> renderers) {
        this.renderers = List.copyOf(renderers);
    }

    /**
     * Renders in the design the DTO carries (already including any one-off download override).
     *
     * @throws BusinessException when no renderer claims the style — which can only happen if a
     *         constant was added to {@link TemplateStyle} without a renderer to match. Failing here
     *         is the point: the alternative is a document silently produced in the wrong design.
     */
    public byte[] render(QuotationResponseDto dto) {
        TemplateStyle style = TemplateStyle.orDefault(dto.getTemplateStyle());
        for (QuotationPdfRenderer renderer : renderers) {
            if (renderer.supports(style)) {
                log.debug("Rendering quotation {} as {} via {}",
                        dto.getPublicId(), style, renderer.getClass().getSimpleName());
                return renderer.render(dto);
            }
        }
        log.error("No PDF renderer supports template style {} (quotation {})", style, dto.getPublicId());
        throw new BusinessException(
                "This quotation design cannot be produced. Please choose another design.",
                HttpStatus.NOT_IMPLEMENTED);
    }
}
