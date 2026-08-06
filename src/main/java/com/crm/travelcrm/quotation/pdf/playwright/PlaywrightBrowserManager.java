package com.crm.travelcrm.quotation.pdf.playwright;

import com.crm.travelcrm.quotation.pdf.LuxuryPdfUnavailableException;
import com.crm.travelcrm.quotation.pdf.config.LuxuryPdfProperties;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Owns the single Chromium process the LUXURY renderer draws on.
 *
 * <p><b>One browser, many contexts.</b> Launching Chromium costs roughly a second of CPU and a few
 * hundred megabytes; doing it per request turns a handful of concurrent downloads into an OOM kill.
 * A {@code BrowserContext} — created and destroyed per PDF by the renderer — is the cheap unit that
 * still gives each render its own cookie jar, cache and storage, so two tenants' documents can never
 * share state through the browser.
 *
 * <p><b>Lazy, never at boot.</b> {@link #browser()} launches on first use behind a double-checked
 * lock. This is what keeps a missing Chromium from being an application-startup failure: a server
 * with no browser installed boots fine and serves CLASSIC/MODERN/PREMIUM normally, and only a
 * request for a Luxury PDF discovers the problem — as a 503 naming it, not a dead application.
 *
 * <p><b>Failures are not cached.</b> A launch that fails leaves the fields null, so the next request
 * tries again. The alternative — remembering "broken" — means an operator who installs the browser
 * has to restart the application to make the feature work, which is a worse default than paying a
 * failed launch per attempt.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlaywrightBrowserManager {

    /**
     * Chromium flags for running inside a container.
     *
     * <p>{@code --disable-dev-shm-usage} is the one that matters: Docker gives a container 64 MB of
     * {@code /dev/shm} by default, Chromium puts rendered page bitmaps there, and an A4 document
     * full of photographs exhausts it — the tab dies mid-render and the PDF comes back empty. The
     * flag moves that scratch space to {@code /tmp} instead, which is why this works without also
     * having to raise {@code shm_size} in compose.
     *
     * <p>{@code --no-sandbox} is required because the image runs as a non-root user without the
     * kernel capabilities Chromium's sandbox needs. Acceptable here and only here: the browser
     * navigates to exactly one loopback URL serving our own template — it never visits user-supplied
     * pages, so the sandbox is not protecting us from anything we do not already control.
     */
    private static final List<String> CONTAINER_SAFE_ARGS = List.of(
            "--no-sandbox",
            "--disable-dev-shm-usage",
            "--disable-gpu",
            "--disable-extensions",
            "--font-render-hinting=none");

    private final LuxuryPdfProperties properties;

    private volatile Playwright playwright;
    private volatile Browser browser;
    private final Object launchLock = new Object();

    /**
     * The shared browser, launching it if this is the first call.
     *
     * @throws LuxuryPdfUnavailableException when the feature is off or Chromium cannot start — the
     *         caller turns this into a clear API error rather than a different design.
     */
    public Browser browser() {
        if (!properties.isEnabled()) {
            throw new LuxuryPdfUnavailableException(
                    "The Luxury design is not available on this server. Please choose another design.");
        }
        Browser current = browser;
        if (current != null && current.isConnected()) return current;

        synchronized (launchLock) {
            // Re-checked inside the lock: several requests can queue here on the first Luxury
            // download of the day, and without this they would each launch their own Chromium.
            if (browser != null && browser.isConnected()) return browser;
            closeQuietly();
            return launch();
        }
    }

    private Browser launch() {
        long start = System.nanoTime();
        try {
            Playwright pw = Playwright.create();
            Browser b = pw.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(properties.isBrowserHeadless())
                    .setArgs(CONTAINER_SAFE_ARGS));
            this.playwright = pw;
            this.browser = b;
            log.info("Chromium launched for Luxury PDF rendering in {} ms",
                    (System.nanoTime() - start) / 1_000_000);
            return b;
        } catch (Exception ex) {
            // Fields stay null so the next request retries. The cause is logged, not returned: it
            // carries local filesystem paths to the browser bundle.
            closeQuietly();
            log.error("Could not launch Chromium for Luxury PDF rendering: {}", ex.getMessage(), ex);
            throw new LuxuryPdfUnavailableException(
                    "The Luxury design could not be prepared on this server. Please choose another design.");
        }
    }

    /**
     * Shuts the browser down with the application context.
     *
     * <p>Without this the Chromium process outlives a redeploy and keeps its memory: on a VPS that
     * restarts the service a few times during a release, the orphans are what eventually exhaust
     * the box.
     */
    @PreDestroy
    public void shutdown() {
        synchronized (launchLock) {
            if (browser != null || playwright != null) {
                log.info("Closing Chromium used for Luxury PDF rendering");
            }
            closeQuietly();
        }
    }

    /** Best-effort teardown — a failure closing one half must not prevent closing the other. */
    private void closeQuietly() {
        try {
            if (browser != null) browser.close();
        } catch (Exception ex) {
            log.warn("Error closing Chromium browser: {}", ex.getMessage());
        } finally {
            browser = null;
        }
        try {
            if (playwright != null) playwright.close();
        } catch (Exception ex) {
            log.warn("Error closing Playwright: {}", ex.getMessage());
        } finally {
            playwright = null;
        }
    }
}
