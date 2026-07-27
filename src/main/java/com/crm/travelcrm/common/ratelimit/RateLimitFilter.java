package com.crm.travelcrm.common.ratelimit;

import com.crm.travelcrm.common.exception.ApiErrorWriter;
import com.crm.travelcrm.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LogManager.getLogger(RateLimitFilter.class);

    // General auth endpoints per IP.
    private static final int      LOGIN_MAX   = 50;
    private static final Duration LOGIN_WIN   = Duration.ofMinutes(1);

    // SuperAdmin password/MFA endpoints carry platform-wide authority; throttle harder.
    private static final int      SUPERADMIN_AUTH_MAX = 10;
    private static final Duration SUPERADMIN_AUTH_WIN = Duration.ofMinutes(5);
    private static final String   SUPERADMIN_LOGIN_PATH = "/api/auth/superadmin/login";
    private static final String   SUPERADMIN_MFA_PATH = "/api/auth/superadmin/mfa/verify";

    // 3 signup attempts per IP per 10 minutes
    private static final int      SIGNUP_MAX  = 3;

    private static final Duration SIGNUP_WIN  = Duration.ofMinutes(10);

    /**
     * The public inbound-lead endpoint. {@code permitAll}, unauthenticated, and it CREATES ROWS — the
     * only such combination in this application, and until now completely unthrottled.
     */
    private static final String   LEAD_WEBHOOK_PREFIX = "/api/webhooks/leads/";

    /**
     * Deliberately generous — this bounds ABUSE, it does not shape legitimate traffic.
     *
     * <p>The asymmetry is the whole design: throttling a real provider is far worse than absorbing some
     * spam. A 429 makes a marketplace retry and, after enough of them, DISABLE the notification URL —
     * which loses every FUTURE lead, not just the throttled one. The ingest pipeline returns 200 even on
     * our own internal failure for exactly this reason. So the ceiling sits an order of magnitude above
     * any plausible burst: no real tenant receives 120 enquiries per minute from one provider IP, while
     * a script hammering the endpoint hits it immediately.
     */
    private static final int      WEBHOOK_MAX = 120;
    private static final Duration WEBHOOK_WIN = Duration.ofMinutes(1);

    private final RateLimitService rateLimitService;
    private final ApiErrorWriter   errorWriter;

    // Comma-separated IPs of trusted reverse proxies / load balancers. X-Forwarded-For is
    // ONLY honored when the direct peer is one of these — otherwise a client could spoof the
    // header to get a fresh bucket and bypass the limit. Empty (default) = trust no proxy.
    @org.springframework.beans.factory.annotation.Value("${app.ratelimit.trusted-proxies:}")
    private String trustedProxiesCsv;
    private java.util.Set<String> trustedProxies = java.util.Set.of();

    @jakarta.annotation.PostConstruct
    void initTrustedProxies() {
        trustedProxies = java.util.Arrays.stream(trustedProxiesCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // Rate-limit the staff auth endpoints, the traveler-portal OTP endpoints, AND the public
        // inbound-lead webhook.
        return !(uri.startsWith("/api/auth/")
                || uri.startsWith("/api/portal/auth/")
                || uri.startsWith(LEAD_WEBHOOK_PREFIX));
    }


    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        String ip   = resolveClientIp(request);

        if (path.startsWith(LEAD_WEBHOOK_PREFIX)) {
            limitLeadWebhook(ip, response, chain, request);
            return;
        }

        boolean isSignup = path.endsWith("/signup");
        boolean isSuperAdminAuth = SUPERADMIN_LOGIN_PATH.equals(path) || SUPERADMIN_MFA_PATH.equals(path);
        int      limit   = isSignup ? SIGNUP_MAX : isSuperAdminAuth ? SUPERADMIN_AUTH_MAX : LOGIN_MAX;
        Duration window  = isSignup ? SIGNUP_WIN : isSuperAdminAuth ? SUPERADMIN_AUTH_WIN : LOGIN_WIN;

        // Key format: rate_limit:api:auth:superadmin:login:<ip>
        String key = "rate_limit:" + path.replace('/', ':') + ":" + ip;

        if (!rateLimitService.isAllowed(key, limit, window)) {
            log.warn("Rate limit exceeded — ip={} path={}", ip, path);
            rejectRequest(response, window);
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Throttle the inbound-lead webhook — <b>keyed and logged on the PREFIX, never the request path.</b>
     *
     * <p>This method exists as a separate branch for one reason, and it is not stylistic: <b>the last
     * segment of this path IS the ingest token.</b> The generic branch above builds its bucket key from
     * the full path and logs that path on rejection — reused here, it would write a live credential into
     * both the rate-limiter's key space and the application log, in plaintext, on every throttled
     * request. "Never log the full token" is one of the framework's four hard rules, and the token is
     * already at risk from access logs and Referer headers without us adding a second leak.
     *
     * <p><b>Keyed by client IP, not by token</b>, for two reasons. A per-token bucket would let anyone
     * mint unlimited buckets by inventing tokens — an unauthenticated memory-growth primitive against
     * the in-memory limiter, which is the disk-fill DoS in a different costume. And an unknown token is
     * rejected before any database work anyway, so per-IP is what actually bounds the abuse.
     *
     * <p>The IP comes from {@link #resolveClientIp}, which honours {@code X-Forwarded-For} ONLY behind a
     * configured trusted proxy. That gate is the entire limiter: without it a client rotates the header
     * for a fresh bucket per request and the ceiling means nothing.
     */
    private void limitLeadWebhook(String ip, HttpServletResponse response, FilterChain chain,
                                  HttpServletRequest request) throws ServletException, IOException {

        String key = "rate_limit:api:webhooks:leads:" + ip;

        if (!rateLimitService.isAllowed(key, WEBHOOK_MAX, WEBHOOK_WIN)) {
            // The prefix, NOT request.getRequestURI() — see the javadoc. This log line is the whole
            // reason this branch is not a one-line `||` on the key above.
            log.warn("Rate limit exceeded — ip={} path={}** (inbound lead webhook)", ip,
                    LEAD_WEBHOOK_PREFIX);
            rejectRequest(response, WEBHOOK_WIN);
            return;
        }

        chain.doFilter(request, response);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        // Only trust X-Forwarded-For when the direct peer is a configured proxy; otherwise the
        // header is attacker-controlled and would let a client rotate IPs to evade the limit.
        if (trustedProxies.contains(remoteAddr)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                // X-Forwarded-For may contain a chain: "client, proxy1, proxy2"
                return xff.split(",")[0].trim();
            }
        }
        return remoteAddr;
    }

    /**
     * Retry-After is set before {@code write()}, which only resets the body buffer — status and
     * headers survive.
     */
    private void rejectRequest(HttpServletResponse response, Duration retryAfter)
            throws IOException {
        response.setHeader("Retry-After", String.valueOf(retryAfter.getSeconds()));
        errorWriter.write(response, ErrorCode.RATE_LIMITED,
                "Too many requests. Please slow down and try again later.");
    }
}
