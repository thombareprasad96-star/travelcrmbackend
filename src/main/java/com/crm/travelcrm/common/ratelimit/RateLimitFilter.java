package com.crm.travelcrm.common.ratelimit;

import com.crm.travelcrm.common.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LogManager.getLogger(RateLimitFilter.class);

    // 3 login attempts per IP per minute
    private static final int      LOGIN_MAX   = 50;
    private static final Duration LOGIN_WIN   = Duration.ofMinutes(1);

    // 3 signup attempts per IP per 10 minutes
    private static final int      SIGNUP_MAX  = 3;

    private static final Duration SIGNUP_WIN  = Duration.ofMinutes(10);

    private final RateLimitService rateLimitService;
    private final ObjectMapper     objectMapper;

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
        // Rate-limit the staff auth endpoints AND the traveler-portal OTP endpoints.
        return !(uri.startsWith("/api/auth/") || uri.startsWith("/api/portal/auth/"));
    }


    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        String ip   = resolveClientIp(request);

        boolean isSignup = path.endsWith("/signup");
        int      limit   = isSignup ? SIGNUP_MAX  : LOGIN_MAX;
        Duration window  = isSignup ? SIGNUP_WIN  : LOGIN_WIN;

        // Key format: rate_limit:api:auth:superadmin:login:<ip>
        String key = "rate_limit:" + path.replace('/', ':') + ":" + ip;

        if (!rateLimitService.isAllowed(key, limit, window)) {
            log.warn("Rate limit exceeded — ip={} path={}", ip, path);
            rejectRequest(response, window);
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

    private void rejectRequest(HttpServletResponse response, Duration retryAfter)
            throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(retryAfter.getSeconds()));

        ErrorResponse body = new ErrorResponse(
                429,
                "TOO_MANY_REQUESTS",
                "Too many requests. Please slow down and try again later.",
                LocalDateTime.now()
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}