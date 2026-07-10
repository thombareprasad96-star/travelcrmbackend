package com.crm.travelcrm.common.web;

import com.crm.travelcrm.common.exception.ApiErrorWriter;
import com.crm.travelcrm.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Renders 403 as {@link com.crm.travelcrm.common.exception.ApiError} JSON.
 *
 * <p>Replaces {@code res.sendError(SC_FORBIDDEN, "Access denied")}, which forwarded to Spring Boot's
 * {@code /error} controller. Because {@code server.error.include-message} defaults to {@code never},
 * the "Access denied" text was stripped and the client received a body with an empty message.
 *
 * <p>A 403 can arrive along two completely different paths, and until now they produced two different
 * response shapes:
 * <ul>
 *   <li>{@code AuthorizationFilter} denies the request → {@code ExceptionTranslationFilter} → here.</li>
 *   <li>{@code @PreAuthorize} denies inside the dispatcher → {@code AuthorizationDeniedException} →
 *       {@code GlobalExceptionHandler}.</li>
 * </ul>
 * The copy below is kept byte-identical to the advice's {@code PERMISSION_DENIED} message so the two
 * paths are indistinguishable to the client.
 *
 * <p>The denial reason is logged, not returned: it can name the missing authority, which tells a
 * prober exactly which permission to go hunting for.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiErrorWriter errorWriter;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.warn("Access denied for {} {}: {}",
                request.getMethod(), request.getRequestURI(), accessDeniedException.toString());
        errorWriter.write(response, ErrorCode.PERMISSION_DENIED,
                "You don't have access to this. Please contact your administrator.");
    }
}