package com.crm.travelcrm.quotation.pdf;

import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.quotation.pdf.dto.LuxuryQuotationPdfDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

/**
 * Serves the Luxury HTML that Chromium loads. Not a public preview — the browser is the only client.
 *
 * <p><b>Why the browser navigates to a URL instead of being handed the HTML string.</b>
 * {@code page.setContent(html)} gives the document a blank origin, so every relative reference in
 * it — the stylesheet, the fallback artwork, the font files — resolves against nothing and silently
 * fails to load. The document still renders; it just renders unstyled, and the failure looks like a
 * broken template rather than a missing base URL. Navigating to a real URL on this application makes
 * the whole of {@code static/} resolve exactly as it does in a browser.
 *
 * <p><b>Security model.</b> The path is unauthenticated at the filter chain (Chromium has no JWT)
 * but carries no lookup: the token IS the payload's only key, it is single-use, it expires in under
 * a minute, and it was minted only after an authenticated, tenant-scoped load of the quotation. A
 * request with a guessed token finds nothing — there is no quotation id in this route to tamper
 * with. See {@link LuxuryPreviewTokenService} for why it works this way.
 *
 * <p>Deliberately outside {@code /api/**} so it can never be mistaken for a client-facing endpoint
 * and so a proxy can drop {@code /internal/**} from the outside world in one rule.
 */
@Controller
@RequestMapping("/internal/pdf/quotations")
@RequiredArgsConstructor
@Slf4j
public class InternalLuxuryPdfPreviewController {

    /** The template name, resolved by Spring Boot's auto-configured Thymeleaf against {@code templates/}. */
    static final String TEMPLATE = "pdf/quotation-luxury";

    /**
     * The Spring-managed engine, NOT the private one inside {@code QuotationPdfService}.
     *
     * <p>That one is configured for {@code TemplateMode.XML} because Flying Saucer needs
     * well-formed XHTML. Chromium wants ordinary HTML5 — void tags unclosed, {@code <br>} legal —
     * and forcing the Luxury template through XML mode would reject the markup the design is written
     * in. Two engines because there are genuinely two contracts.
     */
    private final SpringTemplateEngine templateEngine;
    private final LuxuryPreviewTokenService tokenService;

    @GetMapping(value = "/{token}/luxury-preview", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> preview(@PathVariable String token) {
        LuxuryQuotationPdfDto payload = tokenService.redeem(token);
        if (payload == null) {
            // Unknown, expired and already-redeemed are one answer on purpose — distinguishing them
            // would tell a prober which of their guesses was a real token.
            log.debug("Luxury preview requested with an invalid or spent token");
            throw new ResourceNotFoundException("Preview not found");
        }

        Context ctx = new Context();
        ctx.setVariable("pdf", payload);

        String html = templateEngine.process(TEMPLATE, ctx);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                // The response holds customer PII and is single-use anyway; nothing between here and
                // Chromium may keep a copy.
                .header("Cache-Control", "no-store, no-cache, must-revalidate")
                .header("X-Robots-Tag", "noindex, nofollow")
                .body(html);
    }
}
