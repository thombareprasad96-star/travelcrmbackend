package com.crm.travelcrm.common.web;

import com.crm.travelcrm.common.context.TraceId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Stamps every request with a correlation id, before anything else runs.
 *
 * <p>Registered as a plain {@code Filter} bean at {@link Ordered#HIGHEST_PRECEDENCE}, so it wraps the
 * Spring Security filter chain (order {@code -100}) and therefore also wraps the custom filters
 * inside it — rate-limit, JWT, maintenance, module-entitlement — none of which could otherwise be
 * correlated with the log line they produce.
 *
 * <p>The header is set <b>before</b> {@code chain.doFilter}, not after, so it survives on responses
 * that commit early (SSE) or that never reach a controller (401 from the entry point).
 *
 * <p>This filter only adds context. It does not read, alter, or depend on authentication,
 * {@code TenantContext}, or {@code PlatformContext}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = TraceId.start();
        response.setHeader(TraceId.HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            TraceId.clear();   // ← CRITICAL — pooled threads would otherwise reuse the previous id
        }
    }
}