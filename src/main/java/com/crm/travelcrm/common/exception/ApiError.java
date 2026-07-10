package com.crm.travelcrm.common.exception;

import com.crm.travelcrm.common.context.TraceId;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * The single error envelope for every non-2xx response the API produces — from
 * {@code GlobalExceptionHandler}, from the servlet filters that run upstream of it, and from the
 * Spring Security entry point / access-denied handler. One shape, one parser on the client.
 *
 * <pre>
 * {
 *   "success": false,
 *   "status": 409,
 *   "code": "DUPLICATE_RESOURCE",
 *   "message": "A lead with this email already exists.",
 *   "fieldErrors": { "email": "must be a valid email address" },
 *   "details":     { "restoreAvailable": true, "entityType": "LEAD", "publicId": "…" },
 *   "traceId": "3f9a1c7e2b04",
 *   "timestamp": "2026-07-09T12:00:00"
 * }
 * </pre>
 *
 * <p><b>Why not RFC 7807 {@code ProblemDetail}?</b> Its field names are {@code title}/{@code detail},
 * not {@code message}. This API has exactly one consumer — the first-party React client — which
 * already reads {@code message} everywhere, so 7807 would buy interop nobody needs at the cost of a
 * frontend rewrite. The three ideas worth stealing from it are here: a stable machine-readable code,
 * a structured field-error map, and an open extension slot ({@code details}).
 *
 * <p><b>Safety.</b> {@code message} is always copy written for a human. Raw exception text, SQL,
 * constraint names, and stack traces never reach this object — they are logged against
 * {@code traceId} instead. {@code success} is always {@code false}: this type is only ever an error.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"success", "status", "code", "message", "fieldErrors", "details", "traceId", "timestamp"})
public final class ApiError {

    private final int                status;
    private final String             code;
    private final String             message;
    private final Map<String, String> fieldErrors;
    private final Map<String, Object> details;
    private final String             traceId;
    private final LocalDateTime      timestamp;

    private ApiError(int status,
                     String code,
                     String message,
                     Map<String, String> fieldErrors,
                     Map<String, Object> details,
                     String traceId,
                     LocalDateTime timestamp) {
        this.status      = status;
        this.code        = code;
        this.message     = message;
        this.fieldErrors = fieldErrors;
        this.details     = details;
        this.traceId     = traceId;
        this.timestamp   = timestamp;
    }

    // ── Factories ────────────────────────────────────────────────────────────

    /** Uses the code's canonical status. The common case. */
    public static ApiError of(ErrorCode code, String message) {
        return of(code.defaultStatus(), code, message);
    }

    /** Status override — only for {@link BusinessException}, which picks its own. */
    public static ApiError of(HttpStatus status, ErrorCode code, String message) {
        return new ApiError(status.value(), code.name(), message,
                null, null, TraceId.current(), LocalDateTime.now());
    }

    // ── Copy-with (preserves timestamp + traceId) ────────────────────────────

    /** Attaches per-field messages. Present ⇒ the client renders inline, never as a toast. */
    public ApiError withFieldErrors(Map<String, String> newFieldErrors) {
        return new ApiError(status, code, message,
                (newFieldErrors == null || newFieldErrors.isEmpty()) ? null : newFieldErrors,
                details, traceId, timestamp);
    }

    /** Attaches an extension payload (e.g. the restore hint on a soft-delete collision). */
    public ApiError withDetails(Map<String, Object> newDetails) {
        return new ApiError(status, code, message, fieldErrors,
                (newDetails == null || newDetails.isEmpty()) ? null : newDetails,
                traceId, timestamp);
    }

    // ── Getters (drive Jackson serialization) ────────────────────────────────

    /** Always false — this type only ever represents a failure. */
    public boolean isSuccess()                  { return false; }
    public int getStatus()                      { return status; }
    public String getCode()                     { return code; }
    public String getMessage()                  { return message; }
    public Map<String, String> getFieldErrors() { return fieldErrors; }
    public Map<String, Object> getDetails()     { return details; }
    public String getTraceId()                  { return traceId; }
    public LocalDateTime getTimestamp()         { return timestamp; }
}