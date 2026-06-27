package com.crm.travelcrm.common.exception;

import com.crm.travelcrm.booking.exception.BookingNotFoundException;
import com.crm.travelcrm.customer.exception.CustomerNotFoundException;
import com.crm.travelcrm.customer.exception.DuplicateCustomerException;
import com.crm.travelcrm.lead.exception.DuplicateLeadException;
import com.crm.travelcrm.lead.exception.LeadNotFoundException;
import com.crm.travelcrm.tenent.exception.DuplicateTenantException;
import com.crm.travelcrm.tenent.exception.TenantNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        log.warn("Email already exists: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(DuplicateLeadException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateLead(DuplicateLeadException ex) {
        log.warn("Duplicate lead: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage());
    }

    /**
     * Create collided only with a soft-deleted (trashed) record. Returns a 409 whose
     * {@code errors} payload carries the trashed record's type + publicId so the UI can
     * offer Restore instead of a dead "already exists" error. Keeps the standard
     * {@code ApiResponse} envelope shape the frontend already unwraps.
     */
    @ExceptionHandler(RestoreAvailableException.class)
    public ResponseEntity<com.crm.travelcrm.common.dto.ApiResponse<Void>> handleRestoreAvailable(
            RestoreAvailableException ex) {
        log.info("Restore-available match on create: {} {}", ex.getEntityType(), ex.getPublicId());
        Map<String, Object> restore = new HashMap<>();
        restore.put("restoreAvailable", true);
        restore.put("entityType", ex.getEntityType());
        restore.put("publicId", ex.getPublicId());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(com.crm.travelcrm.common.dto.ApiResponse.failure(ex.getMessage(), restore, 409));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(LeadNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleLeadNotFound(LeadNotFoundException ex) {
        log.warn("Lead not found: {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedException ex) {
        log.warn("Unauthorized access: {}", ex.getMessage());
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", ex.getMessage());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        log.warn("Bad credentials attempt");
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid email or password !");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        String summary = fieldErrors.isEmpty()
                ? Objects.requireNonNull(ex.getBindingResult().getFieldError()).getDefaultMessage()
                : "Validation failed: " + fieldErrors;

        log.warn("Validation failed: {}", fieldErrors);
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", summary);
    }

    // Unparseable body — e.g. a `role` value that is not one of the Role enum
    // constants, or a malformed date. Surfaces as 400, never a 500.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException ex) {
        log.warn("Malformed request body: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "Invalid request body. Check that 'role' is one of MANAGER or TRAVEL_AGENT "
                        + "and that enum values and date formats (yyyy-MM-dd) are valid.");
    }

    @ExceptionHandler(BookingNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleBookingNotFound(BookingNotFoundException ex) {
        log.warn("Booking not found: {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCustomerNotFound(CustomerNotFoundException ex) {
        log.warn("Customer not found: {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(DuplicateCustomerException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateCustomer(DuplicateCustomerException ex) {
        log.warn("Duplicate customer: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage());
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex) {
        log.warn("Business rule violation: {}", ex.getMessage());
        return build(ex.getStatus(), ex.getStatus().name(), ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
    }

    /**
     * Client / SSE connection dropped mid-write (e.g. a browser tab with an open
     * {@code GET /api/notifications/stream} was closed). The response is already
     * committed — often as {@code text/event-stream} — so we can neither write an
     * error body nor recover. Swallow it with a debug log instead of letting it
     * fall through to {@link #handleGenericException} (which would try to serialize
     * an {@link ErrorResponse} into the committed stream and raise a secondary
     * {@code HttpMessageNotWritableException}).
     */
    @ExceptionHandler({ IOException.class, AsyncRequestNotUsableException.class })
    public void handleClientDisconnect(Exception ex) {
        log.debug("Client connection aborted: {}", ex.getMessage());
    }

    // Unknown URL — no controller mapping and no matching static resource. Spring
    // raises this for mistyped paths (e.g. a stray trailing space in
    // "/api/quotations/{id}/pdf"). Return a clean 404 instead of letting it fall
    // through to the generic 500 handler below.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        log.warn("No resource found for path: {}", ex.getResourcePath());
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "Resource not found: " + ex.getResourcePath());
    }

    // Cross-tenant access blocked by TenantEntityListener (pre-persist / pre-update).
    // Must surface as 403, not a generic 500.
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ErrorResponse> handleSecurity(SecurityException ex) {
        log.warn("Security violation: {}", ex.getMessage());
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You do not have permission to perform this action.");
    }

    // Optimistic-lock conflict (e.g. two concurrent updates of the same vendor).
    // 409 lets the client retry with fresh data instead of silently losing a write.
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(
            org.springframework.dao.OptimisticLockingFailureException ex) {
        log.warn("Optimistic lock conflict: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, "CONFLICT",
                "This record was changed by someone else. Please reload and try again.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unexpected error: ", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred. Please try again later.");
    }

    @ExceptionHandler(TenantNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTenantNotFound(TenantNotFoundException ex) {
        log.warn("Tenant not found: {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(DuplicateTenantException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateTenant(DuplicateTenantException ex) {
        log.warn("Duplicate tenant: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage());
    }


    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AuthorizationDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());

        return build(
                HttpStatus.FORBIDDEN,
                "PERMISSION_DENIED",
                "You don't have access to this. Please contact your administrator."
        );
    }


    // ── Private helper ────────────────────────────────────────────────────────
    private ResponseEntity<ErrorResponse> build(HttpStatus status, String error, String message) {
        return new ResponseEntity<>(
                new ErrorResponse(status.value(), error, message, LocalDateTime.now()),
                status
        );
    }
}