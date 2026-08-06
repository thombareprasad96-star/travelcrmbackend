package com.crm.travelcrm.quotation.pdf.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Everything the LUXURY (Chromium) PDF path is allowed to be tuned by, in one place.
 *
 * <p>Deliberately {@code @ConfigurationProperties} rather than scattered {@code @Value}: the
 * renderer, the browser manager and the internal preview controller all need overlapping subsets of
 * these, and three copies of {@code @Value("${pdf.luxury.render-timeout-ms:60000}")} is how a
 * default drifts between them.
 *
 * <p><b>{@link #enabled} defaults to true but costs nothing until first use</b> — the browser is
 * launched lazily on the first LUXURY render, never at boot, so an environment with no Chromium
 * starts normally and only fails when someone actually asks for a Luxury PDF.
 */
@Component
@ConfigurationProperties(prefix = "pdf.luxury")
@Getter
@Setter
public class LuxuryPdfProperties {

    /**
     * Master switch. When false the LUXURY style answers with a clear "unavailable" error and the
     * browser is never launched; CLASSIC/MODERN/PREMIUM are untouched either way.
     */
    private boolean enabled = true;

    /** Headless Chromium. Only ever set false when debugging a render locally. */
    private boolean browserHeadless = true;

    /** Cap on how long Chromium may take to load the internal preview URL. */
    private long navigationTimeoutMs = 30_000;

    /**
     * Cap on the whole render — waiting for {@code window.__PDF_READY__}, fonts and images
     * included. Longer than the navigation timeout on purpose: navigation only fetches the HTML,
     * this covers every remote image the document references.
     */
    private long renderTimeoutMs = 60_000;

    /**
     * How many Luxury PDFs may render at once. Each one is a Chromium BrowserContext holding a full
     * A4 document with photographs in memory, so this is a memory ceiling, not a throughput knob —
     * an unbounded value turns a burst of downloads into an OOM kill of the whole application.
     */
    private int maxConcurrentJobs = 2;

    /**
     * How long a caller may wait for a concurrency slot before being told the renderer is busy.
     * Without this a queued request would sit on a servlet thread until the client gave up.
     */
    private long queueTimeoutMs = 20_000;

    /**
     * Where Chromium reaches this application to fetch the preview HTML. Loopback by design: the
     * page is fetched by a browser running on this same host, so it never needs to leave it, and
     * the internal preview route stays unreachable from outside.
     *
     * <p>{@code ${server.port}} is resolved by Spring, so this follows a port override
     * automatically. Behind a reverse proxy this must stay the LOCAL address — pointing it at the
     * public hostname would send the render out through nginx and back.
     */
    private String internalBaseUrl = "http://127.0.0.1:${server.port}";

    /**
     * Lifetime of the one-shot token that authorises Chromium's fetch of the preview HTML. Short by
     * design: the browser navigates within milliseconds of the token being minted, so a minute is
     * already generous, and the token is burned on first use regardless.
     */
    private long previewTokenTtlSeconds = 60;
}
