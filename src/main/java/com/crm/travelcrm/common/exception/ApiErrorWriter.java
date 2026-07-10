package com.crm.travelcrm.common.exception;

import com.crm.travelcrm.common.context.TraceId;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * Serializes an {@link ApiError} straight onto an {@link HttpServletResponse}.
 *
 * <p>Needed because three of the four places that produce errors sit <b>outside</b> Spring MVC and
 * therefore cannot return a {@code ResponseEntity}: the custom servlet filters (rate-limit,
 * maintenance, module-entitlement), the Spring Security {@code AuthenticationEntryPoint}, and the
 * {@code AccessDeniedHandler}. Before this class each of them hand-rolled its own JSON — which is
 * exactly how the API ended up with five different error shapes.
 *
 * <p><b>The committed-response guard is load-bearing.</b> {@code GET /api/notifications/stream} is an
 * SSE endpoint: its response is committed as {@code text/event-stream} the moment the first event is
 * flushed. Writing an error body into it would corrupt the stream and raise a secondary
 * {@code HttpMessageNotWritableException}. Same reasoning as the {@code IOException} swallow in
 * {@link GlobalExceptionHandler#handleClientDisconnect}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiErrorWriter {

    private final ObjectMapper objectMapper;

    /**
     * Writes {@code error} as JSON, setting status, content type and the {@code X-Trace-Id} header.
     * Silently does nothing if the response is already committed.
     */
    public void write(HttpServletResponse response, ApiError error) throws IOException {
        if (response.isCommitted()) {
            log.debug("Response already committed — dropping {} body (traceId={})",
                    error.getCode(), error.getTraceId());
            return;
        }

        // Discard anything a downstream component buffered. Headers and status survive this,
        // so callers may set e.g. Retry-After before calling write().
        response.resetBuffer();

        response.setStatus(error.getStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (error.getTraceId() != null) {
            response.setHeader(TraceId.HEADER, error.getTraceId());
        }

        byte[] body = objectMapper.writeValueAsBytes(error);
        try {
            OutputStream out = response.getOutputStream();
            out.write(body);
            out.flush();
        } catch (IllegalStateException alreadyUsingWriter) {
            // Something downstream called getWriter() first; the servlet spec forbids mixing the two.
            PrintWriter writer = response.getWriter();
            writer.write(new String(body, StandardCharsets.UTF_8));
            writer.flush();
        }
    }

    /** Convenience for the common "code + copy" case. */
    public void write(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        write(response, ApiError.of(code, message));
    }
}