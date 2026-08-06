package com.crm.travelcrm.quotation.pdf;

import com.crm.travelcrm.quotation.dto.QuotationResponseDto;
import com.crm.travelcrm.quotation.enums.TemplateStyle;
import com.crm.travelcrm.quotation.service.QuotationPdfService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * CLASSIC, MODERN and PREMIUM — the OpenPDF/Flying Saucer path, unchanged.
 *
 * <p><b>A pure delegation, deliberately.</b> {@link QuotationPdfService} was not modified when the
 * Luxury design landed: not a template name, not a font registration, not the section-order rule.
 * This class only puts the existing behaviour behind the renderer interface so the router has one
 * shape to call. That is what makes "the existing three still render byte-identically" a structural
 * claim rather than a hope — there is no new code on their path to have broken them.
 */
@Component
@RequiredArgsConstructor
public class LegacyQuotationPdfRenderer implements QuotationPdfRenderer {

    private final QuotationPdfService quotationPdfService;

    /**
     * Everything except Luxury, expressed as {@code isLegacyEngine()} rather than an explicit
     * {@code CLASSIC || MODERN || PREMIUM} list: a fifth design added to the enum should fail loudly
     * at the router ("no renderer") instead of silently arriving here and being handed to an engine
     * whose CSS it may not have been written for.
     */
    @Override
    public boolean supports(TemplateStyle style) {
        return TemplateStyle.orDefault(style).isLegacyEngine();
    }

    @Override
    public byte[] render(QuotationResponseDto dto) {
        return quotationPdfService.render(dto);
    }
}
