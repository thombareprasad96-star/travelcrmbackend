package com.crm.travelcrm.common.web;

import com.crm.travelcrm.common.context.TraceId;
import com.crm.travelcrm.common.exception.ApiError;
import com.crm.travelcrm.common.exception.ApiErrorWriter;
import com.crm.travelcrm.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * The safety net for exceptions thrown by <b>servlet filters</b>, which {@code @RestControllerAdvice}
 * can never see.
 *
 * <p>Spring Security's chain here is
 * {@code rateLimit → jwtAuth → maintenance → moduleAccess → … → ExceptionTranslationFilter →
 * AuthorizationFilter → DispatcherServlet}. All four custom filters are registered ahead of
 * {@code UsernamePasswordAuthenticationFilter}, which places them <b>upstream of both</b>
 * {@code ExceptionTranslationFilter} and the {@code DispatcherServlet}. An exception thrown in any of
 * them bypasses {@code GlobalExceptionHandler} entirely and used to render as a container whitelabel
 * page. This filter sits outside the whole security chain and renders the standard {@link ApiError}
 * instead.
 *
 * <p>Scope is limited to {@code /api/**}: a static asset or an SPA route should not receive JSON, so
 * anything else is rethrown untouched.
 *
 * <p>Async and ERROR dispatches are skipped by {@link OncePerRequestFilter}'s defaults, which keeps
 * this filter away from in-flight SSE streams. The {@code isCommitted()} check in
 * {@link ApiErrorWriter} is the second line of defence for the same case.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)   // immediately inside TraceIdFilter
@RequiredArgsConstructor
public class ApiErrorFilter extends OncePerRequestFilter {

    private final ApiErrorWriter errorWriter;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);

        } catch (Exception ex) {
            if (!request.getRequestURI().startsWith("/api/")) {
                throw ex;   // not ours to render as JSON
            }
            if (response.isCommitted()) {
                // Typically a client that walked away mid-stream. Nothing can be written; the
                // connection is already gone. Mirrors GlobalExceptionHandler#handleClientDisconnect.
                log.debug("Filter-level failure on a committed response ({}): {}",
                        request.getRequestURI(), ex.toString());
                return;
            }
            errorWriter.write(response, toApiError(ex, request));
        }
    }

    /**
     * Only three outcomes are possible this far up the chain, and none of them may echo
     * {@code ex.getMessage()} — a filter-level failure can carry tenant ids, tokens, or SQL.
     */
    private ApiError toApiError(Exception ex, HttpServletRequest request) {
        if (ex instanceof AuthenticationException) {
            log.warn("Authentication failure in filter chain [{}]: {}", request.getRequestURI(), ex.toString());
            return ApiError.of(ErrorCode.UNAUTHENTICATED,
                    "Your session has expired or you are not signed in. Please sign in again.");
        }
        if (ex instanceof AccessDeniedException) {
            log.warn("Access denied in filter chain [{}]: {}", request.getRequestURI(), ex.toString());
            return ApiError.of(ErrorCode.PERMISSION_DENIED,
                    "You don't have access to this. Please contact your administrator.");
        }
        // Full stack goes to the log, correlated by traceId; the caller gets the id and nothing else.
        log.error("Unhandled filter-level error [{}] traceId={}", request.getRequestURI(), TraceId.current(), ex);
        return ApiError.of(ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred. Please try again later.");
    }
}