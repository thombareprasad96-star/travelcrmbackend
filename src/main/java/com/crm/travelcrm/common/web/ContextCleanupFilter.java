package com.crm.travelcrm.common.web;

import com.crm.travelcrm.common.context.PlatformContext;
import com.crm.travelcrm.common.context.TenantContext;
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
 * Guarantees {@link TenantContext} and {@link PlatformContext} are cleared at the end of every
 * request, on every path, no matter who set them.
 *
 * <p><b>Why this exists.</b> {@code JwtAuthFilter} and {@code TravelerJwtAuthFilter} each clear
 * both contexts in a {@code finally} — but only after an early {@code return} for requests with no
 * {@code Bearer} header. Several flows deliberately authenticate <i>without</i> a header and set
 * {@code TenantContext} themselves:
 *
 * <ul>
 *   <li>{@code GET /api/notifications/stream?token=} — {@code EventSource} cannot send headers.</li>
 *   <li>{@code POST /api/portal/auth/request-otp|verify-otp} — pre-authentication, by definition.</li>
 * </ul>
 *
 * <p>Those set a tenant on a pooled Tomcat thread that nothing then cleared, so the next request to
 * reuse the thread inherited a stale tenant. Because {@code TenantFilterAspect} enables
 * {@code tenantFilter} from {@code TenantContext} on {@code @Transactional} entry — <i>before</i> the
 * method body runs — a stale value silently narrowed queries that are meant to run un-scoped:
 * {@code TravelerAuthServiceImpl.resolveCustomer} (the deliberate cross-tenant lookup that finds
 * which tenant an OTP identifier belongs to) and the public quotation share link. Symptom: a
 * traveler intermittently receives no OTP, and a valid share link intermittently 404s, depending on
 * which thread served the request.
 *
 * <p><b>Placement.</b> Registered as a plain {@code Filter} bean just inside {@link TraceIdFilter}
 * and outside the Spring Security chain (order {@code -100}), so its {@code finally} runs after
 * every filter, both security chains, and any path matched by neither. Cleanup is unconditional and
 * idempotent — {@code ThreadLocal.remove()} on an unset value is a no-op — so this is a safety net,
 * not a replacement: the auth filters still clear eagerly on their own paths.
 *
 * <p><b>Async is safe.</b> {@link OncePerRequestFilter} does not re-run on async dispatch, and for
 * an {@code SseEmitter} the container returns the worker thread to the pool once {@code doFilter}
 * returns while holding only the connection open. Clearing here is therefore correct <i>because</i>
 * the response is async, not in spite of it — the thread is going back in the pool at exactly this
 * point, which is precisely when it must not carry a tenant.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ContextCleanupFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            PlatformContext.clear();
        }
    }
}