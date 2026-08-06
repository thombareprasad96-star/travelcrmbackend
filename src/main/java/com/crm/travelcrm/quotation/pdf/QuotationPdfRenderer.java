package com.crm.travelcrm.quotation.pdf;

import com.crm.travelcrm.quotation.dto.QuotationResponseDto;
import com.crm.travelcrm.quotation.enums.TemplateStyle;

/**
 * One way of turning a quotation into PDF bytes.
 *
 * <p>The abstraction exists because the fourth design does not share an engine with the first
 * three: CLASSIC/MODERN/PREMIUM are laid out by OpenPDF's Flying Saucer fork, LUXURY by headless
 * Chromium. Without this split the alternative was a second branch inside
 * {@code QuotationPdfService} — which would have put a Playwright import into the class that renders
 * every existing quotation, and made a Chromium classpath problem a problem for all four designs.
 *
 * <p>Implementations answer {@link #supports(TemplateStyle)} rather than being looked up from a map,
 * so adding a fifth design means adding a bean and nothing else.
 */
public interface QuotationPdfRenderer {

    /** Whether this renderer owns the given design. Exactly one bean must answer true per style. */
    boolean supports(TemplateStyle style);

    /**
     * Renders the finished document.
     *
     * @param dto the quotation as the read path already built it — the renderer never re-queries or
     *            recalculates; {@code dto.getTemplateStyle()} is authoritative and already carries
     *            any one-off download override the caller applied.
     * @return the PDF bytes; never empty — an implementation that cannot produce a valid document
     *         throws rather than returning a zero-length or truncated array a browser would open as
     *         a blank page.
     */
    byte[] render(QuotationResponseDto dto);
}
