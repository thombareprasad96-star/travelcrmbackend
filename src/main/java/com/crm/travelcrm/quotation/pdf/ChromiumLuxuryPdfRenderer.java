package com.crm.travelcrm.quotation.pdf;

import com.crm.travelcrm.quotation.dto.QuotationResponseDto;
import com.crm.travelcrm.quotation.enums.TemplateStyle;
import com.crm.travelcrm.quotation.pdf.config.LuxuryPdfProperties;
import com.crm.travelcrm.quotation.pdf.dto.LuxuryQuotationPdfDto;
import com.crm.travelcrm.quotation.pdf.mapper.LuxuryQuotationPdfMapper;
import com.crm.travelcrm.quotation.pdf.playwright.PlaywrightBrowserManager;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Margin;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The LUXURY renderer: Thymeleaf → an internal URL → headless Chromium → PDF.
 *
 * <p><b>Why this design cannot use the existing engine.</b> The Luxury stylesheet is built on CSS
 * Grid, flexbox, {@code object-fit} and gradient overlays. Flying Saucer implements roughly CSS 2.1:
 * it does not fail on those rules, it <em>ignores</em> them, so the document would render as a
 * stack of unstyled blocks — a plausible-looking PDF that is wrong. Chromium is the only engine in
 * reach that lays the design out as designed.
 *
 * <p><b>Request shape.</b> One shared browser (see {@link PlaywrightBrowserManager}), a fresh
 * {@code BrowserContext} per PDF, both context and page closed in a {@code finally}. A leaked
 * context keeps its page's bitmaps alive for the life of the process, so the close is not tidiness.
 */
@Component
@Slf4j
public class ChromiumLuxuryPdfRenderer implements QuotationPdfRenderer {

    /**
     * The flag the template raises once its fonts and images have settled.
     *
     * <p>Printing on {@code load} alone produces documents with grey boxes where photographs should
     * be: {@code load} fires when the HTML and its subresources are requested, not when a remote
     * Cloudinary image has actually decoded. The template resolves this flag itself — including on
     * image errors, so one dead URL cannot hang the render forever.
     */
    private static final String READY_FLAG = "window.__PDF_READY__ === true";

    private final PlaywrightBrowserManager browserManager;
    private final LuxuryQuotationPdfMapper mapper;
    private final LuxuryPreviewTokenService tokenService;
    private final LuxuryPdfProperties properties;
    private final String serverPort;

    /**
     * The concurrency gate.
     *
     * <p>Each permit is one live Chromium page holding a full A4 document with photographs. This is
     * a memory ceiling rather than a throughput knob: without it, ten agents downloading at once
     * open ten renders and the JVM is killed by the OOM killer — taking the whole CRM down to serve
     * PDFs. Fair ordering so a burst does not starve the request that arrived first.
     */
    private final Semaphore slots;

    public ChromiumLuxuryPdfRenderer(PlaywrightBrowserManager browserManager,
                                     LuxuryQuotationPdfMapper mapper,
                                     LuxuryPreviewTokenService tokenService,
                                     LuxuryPdfProperties properties,
                                     @Value("${server.port:8080}") String serverPort) {
        this.browserManager = browserManager;
        this.mapper = mapper;
        this.tokenService = tokenService;
        this.properties = properties;
        this.serverPort = serverPort;
        this.slots = new Semaphore(Math.max(1, properties.getMaxConcurrentJobs()), true);
    }

    @Override
    public boolean supports(TemplateStyle style) {
        return TemplateStyle.orDefault(style) == TemplateStyle.LUXURY;
    }

    @Override
    public byte[] render(QuotationResponseDto dto) {
        if (!properties.isEnabled()) {
            throw new LuxuryPdfUnavailableException(
                    "The Luxury design is not available on this server. Please choose another design.");
        }

        // Mapped BEFORE a slot is taken: this is pure formatting over data already in memory, and
        // holding a Chromium permit while doing it would shrink effective concurrency for nothing.
        LuxuryQuotationPdfDto payload = mapper.map(dto);

        boolean acquired;
        try {
            acquired = slots.tryAcquire(properties.getQueueTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LuxuryPdfRenderException("The PDF request was interrupted. Please try again.");
        }
        if (!acquired) {
            // 503, not a queue that grows without limit: telling the agent to retry in a moment is
            // better than holding their request open until the browser times out anyway.
            throw new LuxuryPdfUnavailableException(
                    "Too many Luxury PDFs are being generated right now. Please try again in a moment.");
        }

        try {
            // ORDER IS LOAD-BEARING: the browser is obtained BEFORE the token is minted.
            //
            // Minting first looks harmless and is not. The token's TTL starts ticking the moment it
            // is created, and the very first Luxury render of a process pays for the cold Chromium
            // launch — measured at 80+ seconds on a Windows dev machine with a freshly downloaded
            // bundle. The token expired before the browser ever navigated, the preview answered 404,
            // and the render then sat until its own timeout before failing. Obtaining the browser
            // first means the TTL only has to cover the navigation itself, which is milliseconds.
            Browser browser = browserManager.browser();   // throws LuxuryPdfUnavailableException
            String token = tokenService.mint(payload);
            return renderWithBrowser(browser, token, dto);
        } finally {
            slots.release();
        }
    }

    private byte[] renderWithBrowser(Browser browser, String token, QuotationResponseDto dto) {
        long start = System.nanoTime();

        BrowserContext context = null;
        Page page = null;
        try {
            // A per-request context, never a shared one: it isolates cache and storage between two
            // tenants' documents, and closing it is what actually frees the render's memory.
            context = browser.newContext(new Browser.NewContextOptions()
                    // Chromium prints @media screen rules unless told the medium is print; the
                    // template's page-break and background rules live under @media print.
                    .setJavaScriptEnabled(true));
            context.setDefaultNavigationTimeout(properties.getNavigationTimeoutMs());
            context.setDefaultTimeout(properties.getRenderTimeoutMs());

            page = context.newPage();
            page.navigate(previewUrl(token), new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(properties.getNavigationTimeoutMs()));

            // Wait for the template's own signal rather than a fixed sleep. A sleep is either too
            // short (photographs missing) or too long (every download pays for the worst case).
            page.waitForFunction(READY_FLAG, null, new Page.WaitForFunctionOptions()
                    .setTimeout(properties.getRenderTimeoutMs()));

            // A rendered-but-empty body is the failure mode a readiness flag cannot catch: the flag
            // is raised by script that runs even if Thymeleaf produced an empty shell.
            if (page.querySelector(".page") == null) {
                throw new LuxuryPdfRenderException(
                        "We couldn't generate the Luxury PDF. Please try again.");
            }

            byte[] pdf = page.pdf(new Page.PdfOptions()
                    .setFormat("A4")
                    .setPrintBackground(true)
                    // The template declares its own @page size; honouring it keeps the design's
                    // bleeds aligned instead of letting the format above rescale them.
                    .setPreferCSSPageSize(true)
                    // No Playwright header/footer: every page in the template already carries its
                    // own footer and page number, and a second one would print over the artwork.
                    .setDisplayHeaderFooter(false)
                    .setMargin(new Margin().setTop("0").setRight("0").setBottom("0").setLeft("0")));

            assertUsable(pdf, dto);
            log.debug("Luxury PDF generated for {} | {} bytes in {} ms",
                    dto.getPublicId(), pdf.length, (System.nanoTime() - start) / 1_000_000);
            return pdf;

        } catch (LuxuryPdfUnavailableException | LuxuryPdfRenderException ex) {
            throw ex;
        } catch (Exception ex) {
            // The cause stays in the log. GlobalExceptionHandler echoes a BusinessException message
            // verbatim to the client, and this one carries the internal URL and Chromium internals.
            log.error("Luxury PDF render failed for quotation {}: {}",
                    dto.getPublicId(), ex.getMessage(), ex);
            throw new LuxuryPdfRenderException("We couldn't generate the Luxury PDF. Please try again.");
        } finally {
            closeQuietly(page, context);
        }
    }

    /**
     * Rejects a document that would open as a blank or corrupt file.
     *
     * <p>Chromium can return bytes after a partial failure, and an empty PDF is worse than an error:
     * the agent sends it to a customer before discovering it does not open. The magic-number check
     * is cheap and catches the case where something other than a PDF came back entirely.
     */
    private void assertUsable(byte[] pdf, QuotationResponseDto dto) {
        boolean valid = pdf != null && pdf.length > 1024
                && pdf[0] == '%' && pdf[1] == 'P' && pdf[2] == 'D' && pdf[3] == 'F';
        if (!valid) {
            log.error("Luxury PDF for quotation {} came back unusable ({} bytes)",
                    dto.getPublicId(), pdf == null ? 0 : pdf.length);
            throw new LuxuryPdfRenderException("We couldn't generate the Luxury PDF. Please try again.");
        }
    }

    /**
     * Loopback URL Chromium fetches the HTML from.
     *
     * <p>The configured base may still contain the literal {@code ${server.port}} when the property
     * was set without Spring's placeholder resolution in play (a plain environment variable, for
     * instance), so it is substituted here rather than trusted to have been resolved.
     */
    private String previewUrl(String token) {
        String base = properties.getInternalBaseUrl().replace("${server.port}", serverPort);
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/internal/pdf/quotations/" + token + "/luxury-preview";
    }

    /** Page then context, each independently — a failure closing the page must not leak the context. */
    private void closeQuietly(Page page, BrowserContext context) {
        try {
            if (page != null) page.close();
        } catch (Exception ex) {
            log.warn("Error closing Luxury PDF page: {}", ex.getMessage());
        }
        try {
            if (context != null) context.close();
        } catch (Exception ex) {
            log.warn("Error closing Luxury PDF browser context: {}", ex.getMessage());
        }
    }
}
