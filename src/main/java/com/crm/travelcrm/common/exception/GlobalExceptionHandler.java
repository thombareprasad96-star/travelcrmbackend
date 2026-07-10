package com.crm.travelcrm.common.exception;

import com.crm.travelcrm.booking.exception.BookingNotFoundException;
import com.crm.travelcrm.common.context.TraceId;
import com.crm.travelcrm.customer.exception.CustomerNotFoundException;
import com.crm.travelcrm.customer.exception.DuplicateCustomerException;
import com.crm.travelcrm.lead.exception.DuplicateLeadException;
import com.crm.travelcrm.lead.exception.LeadNotFoundException;
import com.crm.travelcrm.tenent.exception.DuplicateTenantException;
import com.crm.travelcrm.tenent.exception.TenantNotFoundException;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import jakarta.validation.ConstraintViolation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates everything thrown inside the {@code DispatcherServlet} into the single {@link ApiError}
 * envelope.
 *
 * <p>This advice is only one of four tiers, because it cannot see everything:
 * <ul>
 *   <li>{@code TraceIdFilter} / {@code ApiErrorFilter} — the custom servlet filters run upstream of
 *       the dispatcher and are invisible here.</li>
 *   <li>{@code ApiAuthenticationEntryPoint} / {@code ApiAccessDeniedHandler} — Spring Security's
 *       {@code ExceptionTranslationFilter} never delegates to a {@code @ControllerAdvice}.</li>
 *   <li><b>this class</b> — everything else.</li>
 * </ul>
 *
 * <h2>Two rules that must survive any future edit</h2>
 * <ol>
 *   <li><b>No exception's own message reaches the client unless a human wrote it for a human.</b>
 *       {@code getMessage()} on a JDK, Hibernate, or Jackson exception carries SQL, constraint names,
 *       column values, and tenant ids. Those go to the log, keyed by {@code traceId}.</li>
 *   <li><b>Ambiguity is the point for 401/403/404.</b> A cross-tenant read and a genuinely missing row
 *       must be byte-identical (404), or the API becomes an existence oracle for other tenants' data.</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String GENERIC_500 =
            "An unexpected error occurred. Please try again later.";
    private static final String GENERIC_403 =
            "You don't have access to this. Please contact your administrator.";
    private static final String GENERIC_401 =
            "Your session has expired or you are not signed in. Please sign in again.";
    private static final String GENERIC_CONFLICT =
            "This record conflicts with an existing one. Please review and try again.";

    // ── Domain: not found (all identical on the wire — see rule 2) ───────────

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return build(ApiError.of(ErrorCode.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(LeadNotFoundException.class)
    public ResponseEntity<ApiError> handleLeadNotFound(LeadNotFoundException ex) {
        log.warn("Lead not found: {}", ex.getMessage());
        return build(ApiError.of(ErrorCode.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(BookingNotFoundException.class)
    public ResponseEntity<ApiError> handleBookingNotFound(BookingNotFoundException ex) {
        log.warn("Booking not found: {}", ex.getMessage());
        return build(ApiError.of(ErrorCode.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    public ResponseEntity<ApiError> handleCustomerNotFound(CustomerNotFoundException ex) {
        log.warn("Customer not found: {}", ex.getMessage());
        return build(ApiError.of(ErrorCode.NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(TenantNotFoundException.class)
    public ResponseEntity<ApiError> handleTenantNotFound(TenantNotFoundException ex) {
        log.warn("Tenant not found: {}", ex.getMessage());
        return build(ApiError.of(ErrorCode.NOT_FOUND, ex.getMessage()));
    }

    /**
     * Unknown URL — no controller mapping and no matching static resource. The resource path is
     * <b>not</b> echoed: it is attacker-controlled input, and reflecting it buys the caller nothing
     * they did not already type.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResourceFound(NoResourceFoundException ex) {
        log.warn("No resource found for path: {}", ex.getResourcePath());
        return build(ApiError.of(ErrorCode.NOT_FOUND, "The requested resource was not found."));
    }

    // ── Domain: conflicts ────────────────────────────────────────────────────

    @ExceptionHandler(DuplicateLeadException.class)
    public ResponseEntity<ApiError> handleDuplicateLead(DuplicateLeadException ex) {
        log.warn("Duplicate lead: {}", ex.getMessage());
        return build(ApiError.of(ErrorCode.CONFLICT, ex.getMessage()));
    }

    @ExceptionHandler(DuplicateCustomerException.class)
    public ResponseEntity<ApiError> handleDuplicateCustomer(DuplicateCustomerException ex) {
        log.warn("Duplicate customer: {}", ex.getMessage());
        return build(ApiError.of(ErrorCode.CONFLICT, ex.getMessage()));
    }

    @ExceptionHandler(DuplicateTenantException.class)
    public ResponseEntity<ApiError> handleDuplicateTenant(DuplicateTenantException ex) {
        log.warn("Duplicate tenant: {}", ex.getMessage());
        return build(ApiError.of(ErrorCode.CONFLICT, ex.getMessage()));
    }

    /** Was declared but never handled — it used to fall through to the generic 500. */
    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiError> handleDuplicateResource(DuplicateResourceException ex) {
        log.warn("Duplicate resource: {}", ex.getMessage());
        return build(ApiError.of(ErrorCode.DUPLICATE_RESOURCE, ex.getMessage()));
    }

    /**
     * Create collided only with a soft-deleted (trashed) record. {@code details} carries the trashed
     * record's type + publicId so the UI can offer Restore instead of a dead "already exists" error.
     */
    @ExceptionHandler(RestoreAvailableException.class)
    public ResponseEntity<ApiError> handleRestoreAvailable(RestoreAvailableException ex) {
        log.info("Restore-available match on create: {} {}", ex.getEntityType(), ex.getPublicId());
        Map<String, Object> details = new HashMap<>();
        details.put("restoreAvailable", true);
        details.put("entityType", ex.getEntityType());
        details.put("publicId", ex.getPublicId());
        return build(ApiError.of(ErrorCode.RESTORE_AVAILABLE, ex.getMessage()).withDetails(details));
    }

    /** Two concurrent updates of the same row. 409 lets the client reload rather than lose a write. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("Optimistic lock conflict: {}", ex.getMessage());
        return build(ApiError.of(ErrorCode.OPTIMISTIC_LOCK,
                "This record was changed by someone else. Please reload and try again."));
    }

    /**
     * A database constraint rejected the write.
     *
     * <p><b>Never echo {@code ex.getMostSpecificCause().getMessage()}.</b> That string contains the
     * failing SQL, the table, the constraint name, <i>and the offending value</i> — on a duplicate-email
     * conflict it would hand the caller another tenant's email address. Only the constraint <i>name</i>
     * (a schema identifier, never user data) is inspected, and only to select pre-written copy.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String constraint = constraintName(ex);
        log.warn("Data integrity violation (constraint={}) traceId={}", constraint, TraceId.current(), ex);

        if (constraint == null) {
            // No DB constraint involved — typically a NOT NULL / property-value failure, which is a
            // malformed request rather than a conflict.
            return build(ApiError.of(ErrorCode.BAD_REQUEST,
                    "The data you submitted is incomplete or invalid. Please review and try again."));
        }
        return build(ApiError.of(ErrorCode.CONFLICT, friendlyConstraintMessage(constraint)));
    }

    // ── Validation & malformed input ─────────────────────────────────────────

    /**
     * Bean validation on an {@code @Valid @RequestBody}. Field messages go in {@code fieldErrors} so
     * the client renders them beside the inputs; {@code message} stays a short summary and is never
     * the place the field detail lives. (Previously the message was
     * {@code "Validation failed: {email=must be a valid email}"} — a Java map, stringified, shown to a user.)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        log.warn("Validation failed: {}", fieldErrors);
        return build(ApiError.of(ErrorCode.VALIDATION_ERROR, summarize(fieldErrors))
                .withFieldErrors(fieldErrors));
    }

    /** Bean validation on {@code @Validated} method params / path variables / request params. */
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            jakarta.validation.ConstraintViolationException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (ConstraintViolation<?> v : ex.getConstraintViolations()) {
            String path = v.getPropertyPath() == null ? "" : v.getPropertyPath().toString();
            String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            fieldErrors.putIfAbsent(field.isBlank() ? "request" : field, v.getMessage());
        }
        log.warn("Constraint violation: {}", fieldErrors);
        return build(ApiError.of(ErrorCode.VALIDATION_ERROR, summarize(fieldErrors))
                .withFieldErrors(fieldErrors));
    }

    /**
     * Unparseable body — a bad enum constant, a malformed date, a wrong JSON type. Surfaces as 400,
     * never a 500. Jackson knows which field failed, so name that field instead of guessing on the
     * caller's behalf.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableMessage(HttpMessageNotReadableException ex) {
        log.warn("Malformed request body: {}", ex.getMessage());
        return build(ApiError.of(ErrorCode.MALFORMED_REQUEST, describeUnreadableBody(ex)));
    }

    /** A query/path param that could not be coerced — e.g. {@code ?stage=NOPE} against an enum. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Type mismatch for parameter '{}': {}", ex.getName(), ex.getMessage());
        return build(ApiError.of(ErrorCode.BAD_REQUEST,
                "Invalid value for '" + humanize(ex.getName()) + "'."));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex) {
        log.warn("Missing request parameter: {}", ex.getParameterName());
        return build(ApiError.of(ErrorCode.BAD_REQUEST,
                "'" + humanize(ex.getParameterName()) + "' is required."));
    }

    /**
     * Thrown by application code that decided the request is unusable. This is the exception a
     * developer reaches for when they have a message written for the end user.
     *
     * <p>It had no handler at all until now — {@code PortalDocumentService} and
     * {@code PortalPaymentService} throw it eight times between them, and every one of those
     * ("File exceeds the 10 MB limit.", "Amount exceeds the pending balance.") was reaching the client
     * as a 500 "An unexpected error occurred."
     */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> handleBadRequest(BadRequestException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return build(ApiError.of(ErrorCode.BAD_REQUEST, ex.getMessage()));
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        log.warn("Email already exists: {}", ex.getMessage());
        return build(ApiError.of(ErrorCode.BAD_REQUEST, ex.getMessage()));
    }

    /**
     * {@code IllegalArgumentException} is thrown by the JDK, by Hibernate, and by Jackson — you cannot
     * tell "copy written for a human" apart from "text written for a stack trace". So it no longer
     * carries a user-facing contract: the real message is logged, the caller gets generic copy.
     *
     * <p>The three places in this codebase that genuinely meant it for the user
     * ({@code MasterDropdownController}, {@code ToolFmt} ×2) now throw {@link BadRequestException}
     * instead. Enum parse failures keep their good copy via {@link #handleUnreadableMessage} (request
     * bodies) and {@link #handleTypeMismatch} (query params).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument (traceId={}): {}", TraceId.current(), ex.getMessage());
        return build(ApiError.of(ErrorCode.BAD_REQUEST, "Invalid request. Please check your input."));
    }

    // ── Transport-shaped failures ────────────────────────────────────────────

    /**
     * The multipart limit ({@code spring.servlet.multipart.max-file-size}) is set slightly above the
     * application's own 10 MB rule so {@code PortalDocumentService}'s friendly check normally wins.
     * This is the backstop for a body that is too big to even buffer.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("Upload too large: {}", ex.getMessage());
        return build(ApiError.of(ErrorCode.PAYLOAD_TOO_LARGE, "File exceeds the 10 MB limit."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("Method not supported: {}", ex.getMessage());
        return build(ApiError.of(ErrorCode.METHOD_NOT_ALLOWED, "This action is not supported here."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.warn("Unsupported media type: {}", ex.getMessage());
        return build(ApiError.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "That file type isn't supported here."));
    }

    /**
     * Client / SSE connection dropped mid-write (e.g. a browser tab with an open
     * {@code GET /api/notifications/stream} was closed). The response is already committed — often as
     * {@code text/event-stream} — so we can neither write an error body nor recover. Swallow it with a
     * debug log instead of letting it fall through to {@link #handleGenericException}, which would try
     * to serialize an {@link ApiError} into the committed stream and raise a secondary
     * {@code HttpMessageNotWritableException}.
     *
     * <p>Returns {@code void} on purpose. Do not "fix" this by returning a body.
     */
    @ExceptionHandler({IOException.class, AsyncRequestNotUsableException.class})
    public void handleClientDisconnect(Exception ex) {
        log.debug("Client connection aborted: {}", ex.getMessage());
    }

    // ── Auth & authorization ─────────────────────────────────────────────────

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> handleUnauthorized(UnauthorizedException ex) {
        log.warn("Unauthorized access: {}", ex.getMessage());
        return build(ApiError.of(ErrorCode.UNAUTHENTICATED, ex.getMessage()));
    }

    /**
     * Login with the wrong credentials. Unlike the entry-point 401 this one may say "invalid email or
     * password" — that single message covers both "no such account" and "wrong password", so it still
     * does not enumerate accounts.
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException ex) {
        log.warn("Bad credentials attempt");
        return build(ApiError.of(ErrorCode.UNAUTHENTICATED, "Invalid email or password."));
    }

    /**
     * Covers {@code AuthorizationDeniedException} (from {@code @PreAuthorize}) too, since it extends
     * {@code AccessDeniedException}. The copy is byte-identical to {@code ApiAccessDeniedHandler}'s so
     * a method-security denial and a filter-level denial are indistinguishable to the client.
     *
     * <p>An anonymous caller gets 401 rather than 403 — that is what {@code ExceptionTranslationFilter}
     * would have done had this advice not intercepted first. Telling someone who never signed in that
     * they are "forbidden" sends them looking for a permission problem that does not exist.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        if (isAnonymous()) {
            log.warn("Access denied for an unauthenticated caller: {}", ex.getMessage());
            return build(ApiError.of(ErrorCode.UNAUTHENTICATED, GENERIC_401));
        }
        log.warn("Access denied: {}", ex.getMessage());
        return build(ApiError.of(ErrorCode.PERMISSION_DENIED, GENERIC_403));
    }

    /**
     * Cross-tenant access blocked by {@code TenantEntityListener} (pre-persist / pre-update).
     *
     * <p>Must surface as 403, and the message must <b>never</b> be echoed: it contains both the
     * entity's tenantId and the context's tenantId.
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiError> handleSecurity(SecurityException ex) {
        log.warn("Security violation (traceId={}): {}", TraceId.current(), ex.getMessage());
        return build(ApiError.of(ErrorCode.PERMISSION_DENIED, GENERIC_403));
    }

    // ── Business rules & the catch-all ───────────────────────────────────────

    /** The one exception type whose author explicitly chose both the status and the copy. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusinessException(BusinessException ex) {
        HttpStatus status = ex.getStatus() == null ? HttpStatus.BAD_REQUEST : ex.getStatus();
        if (status.is5xxServerError()) {
            log.error("Business failure (traceId={}): {}", TraceId.current(), ex.getMessage(), ex);
        } else {
            log.warn("Business rule violation: {}", ex.getMessage());
        }
        return build(ApiError.of(status, ErrorCode.fromStatus(status), ex.getMessage()));
    }

    /** Last resort. The stack trace goes to the log against {@code traceId}; the client gets the id. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGenericException(Exception ex) {
        log.error("Unexpected error (traceId={}): ", TraceId.current(), ex);
        return build(ApiError.of(ErrorCode.INTERNAL_ERROR, GENERIC_500));
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private ResponseEntity<ApiError> build(ApiError error) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(error.getStatus());
        if (error.getTraceId() != null) {
            response.header(TraceId.HEADER, error.getTraceId());
        }
        return response.body(error);
    }

    private static boolean isAnonymous() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken;
    }

    /** A one-line summary. The per-field detail lives in {@code fieldErrors}, not in here. */
    private static String summarize(Map<String, String> fieldErrors) {
        if (fieldErrors.isEmpty()) return "Please check your input and try again.";
        if (fieldErrors.size() == 1) {
            Map.Entry<String, String> only = fieldErrors.entrySet().iterator().next();
            return humanize(only.getKey()) + " " + only.getValue() + ".";
        }
        return "Please correct the highlighted fields.";
    }

    /** Walks to the Hibernate constraint failure, if there is one. Returns the constraint name only. */
    private static String constraintName(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                return (name == null || name.isBlank()) ? null : name;
            }
            if (t.getCause() == t) break;   // defensive: self-referencing cause
        }
        return null;
    }

    /**
     * Maps a constraint <i>name</i> to pre-written copy. Substring matching on the schema identifier
     * keeps this resilient to Hibernate's generated names ({@code uk_lead_email_tenant} etc.) without
     * ever touching the exception's message.
     */
    private static String friendlyConstraintMessage(String constraintName) {
        String n = constraintName.toLowerCase();
        if (n.contains("email"))                        return "An account with this email address already exists.";
        if (n.contains("phone") || n.contains("mobile")) return "This phone number is already registered.";
        if (n.contains("gst"))                          return "This GST number is already registered.";
        if (n.contains("pan"))                          return "This PAN number is already registered.";
        if (n.contains("registration") || n.contains("reg_no"))
                                                        return "This registration number is already in use.";
        if (n.contains("code"))                         return "This code is already in use.";
        if (n.contains("slug") || n.contains("name"))   return "A record with this name already exists.";
        return GENERIC_CONFLICT;
    }

    /** Turns a Jackson parse failure into a short message aimed at the person filling the form. */
    private String describeUnreadableBody(HttpMessageNotReadableException ex) {
        Throwable cause = findCause(ex);

        // An @JsonCreator that rejected its input (our enums throw IllegalArgumentException)
        // arrives as ValueInstantiationException — which extends JsonMappingException
        // directly, NOT MismatchedInputException, so it needs its own branch.
        if (cause instanceof ValueInstantiationException vie) {
            return fieldMessage(vie, vie.getType() == null ? null : vie.getType().getRawClass());
        }
        if (cause instanceof InvalidFormatException ife) {
            return fieldMessage(ife, ife.getTargetType());
        }
        if (cause instanceof MismatchedInputException mie) {
            return fieldMessage(mie, mie.getTargetType());
        }
        return "Invalid request. Please check your input.";
    }

    /** Enum-backed fields are dropdowns in the UI, so they get "select" rather than "enter". */
    private static String fieldMessage(JsonMappingException ex, Class<?> target) {
        String label = fieldLabel(ex);
        return target != null && target.isEnum()
                ? "Please select " + label + "."
                : "Please enter a valid " + label + ".";
    }

    /**
     * The cause chain below an InvalidFormatException continues into the underlying
     * DateTimeParseException/NumberFormatException, so walk down to the first Jackson
     * exception rather than taking getRootCause().
     */
    private static Throwable findCause(Throwable ex) {
        for (Throwable t = ex.getCause(); t != null; t = t.getCause()) {
            if (t instanceof JsonMappingException || t instanceof JsonParseException) return t;
        }
        return null;
    }

    /** Deepest field in Jackson's reference path, as a form label: {@code leadSource} → "Lead Source". */
    private static String fieldLabel(JsonMappingException ex) {
        String field = null;
        for (JsonMappingException.Reference ref : ex.getPath()) {
            if (ref.getFieldName() != null) field = ref.getFieldName();
        }
        if (field == null || field.isBlank()) return "a valid value";
        return humanize(field);
    }
    /** {@code leadSource} → "Lead Source". Safe: these are our own identifiers, never user data. */
    private static String humanize(String field) {
        if (field == null || field.isBlank()) return "value";
        String spaced = field.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}