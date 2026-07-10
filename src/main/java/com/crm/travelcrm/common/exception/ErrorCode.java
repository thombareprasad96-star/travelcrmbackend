package com.crm.travelcrm.common.exception;

import org.springframework.http.HttpStatus;

/**
 * The stable, machine-readable half of the error contract.
 *
 * <p>Clients branch on {@code code} — never on {@code message} (copy changes) and never on the HTTP
 * status alone, because a status is not always specific enough to drive UX. The motivating case is
 * <b>403</b>, which splits two ways here: {@link #PERMISSION_DENIED} (this user lacks the permission —
 * show a toast) versus {@link #MODULE_NOT_ENABLED} (the tenant's plan excludes the whole module —
 * show an upgrade prompt). Same status, different screens.
 *
 * <p>Each constant carries the status it normally maps to. {@link BusinessException} may override it
 * (it chooses its own status), which is why {@link #fromStatus(HttpStatus)} exists.
 */
public enum ErrorCode {

    // ── 4xx: the caller can fix it ───────────────────────────────────────────
    /** Bean-validation failure. Always accompanied by {@code fieldErrors}; render inline, not as a toast. */
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    /** A business rule rejected an otherwise well-formed request. */
    BAD_REQUEST(HttpStatus.BAD_REQUEST),
    /** The request body could not be parsed (bad enum constant, malformed date, wrong JSON type). */
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),

    /** Not signed in, or the session/token is no longer usable. Copy is uniform — see ApiAuthenticationEntryPoint. */
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),

    /** Authenticated, but not allowed to perform this action. */
    PERMISSION_DENIED(HttpStatus.FORBIDDEN),
    /** Authenticated and permitted, but the tenant's plan does not include this module. */
    MODULE_NOT_ENABLED(HttpStatus.FORBIDDEN),

    NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),

    CONFLICT(HttpStatus.CONFLICT),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT),
    /** Create collided with a soft-deleted row; {@code details} carries enough to offer "Restore". */
    RESTORE_AVAILABLE(HttpStatus.CONFLICT),
    /** Someone else changed the row first. The client should reload and retry. */
    OPTIMISTIC_LOCK(HttpStatus.CONFLICT),

    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),

    // ── 5xx: the caller cannot fix it ────────────────────────────────────────
    /** Never carries detail. The cause is logged with the traceId; the client only gets the traceId. */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
    MAINTENANCE(HttpStatus.SERVICE_UNAVAILABLE);

    private final HttpStatus defaultStatus;

    ErrorCode(HttpStatus defaultStatus) {
        this.defaultStatus = defaultStatus;
    }

    public HttpStatus defaultStatus() {
        return defaultStatus;
    }

    /**
     * Best-effort code for a status chosen at runtime — used only for {@link BusinessException},
     * which predates this enum and lets each call site pick its own status.
     */
    public static ErrorCode fromStatus(HttpStatus status) {
        if (status == null) return INTERNAL_ERROR;
        return switch (status) {
            case BAD_REQUEST            -> BAD_REQUEST;
            case UNAUTHORIZED           -> UNAUTHENTICATED;
            case FORBIDDEN              -> PERMISSION_DENIED;
            case NOT_FOUND              -> NOT_FOUND;
            case METHOD_NOT_ALLOWED     -> METHOD_NOT_ALLOWED;
            case CONFLICT               -> CONFLICT;
            case PAYLOAD_TOO_LARGE      -> PAYLOAD_TOO_LARGE;
            case UNSUPPORTED_MEDIA_TYPE -> UNSUPPORTED_MEDIA_TYPE;
            case TOO_MANY_REQUESTS      -> RATE_LIMITED;
            default -> status.is5xxServerError() ? INTERNAL_ERROR : BAD_REQUEST;
        };
    }
}