package com.crm.travelcrm.common.exception;

import com.crm.travelcrm.lead.exception.DuplicateLeadException;
import com.crm.travelcrm.lead.exception.LeadNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── 400 Email already exists ──────────────────────────────────────────────
    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        log.warn("Email already exists: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
    }

    // ── 409 Duplicate lead (email or phone) ───────────────────────────────────
    @ExceptionHandler(DuplicateLeadException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateLead(DuplicateLeadException ex) {
        log.warn("Duplicate lead: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage());
    }

    // ── 404 Resource not found ────────────────────────────────────────────────
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    // ── 404 Lead not found ────────────────────────────────────────────────────
    @ExceptionHandler(LeadNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleLeadNotFound(LeadNotFoundException ex) {
        log.warn("Lead not found: {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    // ── 401 Unauthorized ──────────────────────────────────────────────────────
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedException ex) {
        log.warn("Unauthorized access: {}", ex.getMessage());
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", ex.getMessage());
    }

    // ── 401 Bad credentials (wrong email/password) ────────────────────────────
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        log.warn("Bad credentials attempt");
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid email or password");
    }

    // ── 400 DTO / Jakarta Validation errors (returns field-level map) ─────────
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
        // Collect ALL field errors into a map  e.g. { "phone": "...", "email": "..." }
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        // Fall back to the first message if the map is somehow empty
        String summary = fieldErrors.isEmpty()
                ? Objects.requireNonNull(ex.getBindingResult().getFieldError()).getDefaultMessage()
                : "Validation failed: " + fieldErrors;

        log.warn("Validation failed: {}", fieldErrors);
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", summary);
    }

    // ── 400 Malformed JSON or bad enum value ──────────────────────────────────
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException ex) {
        log.warn("Malformed request body: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "Invalid request body. Check enum values and date formats (yyyy-MM-dd).");
    }

    // ── 400 Illegal enum / argument ───────────────────────────────────────────
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
    }

    // ── 500 Fallback ──────────────────────────────────────────────────────────
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unexpected error: ", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred. Please try again later.");
    }

    // ── Private helper ────────────────────────────────────────────────────────
    private ResponseEntity<ErrorResponse> build(HttpStatus status, String error, String message) {
        return new ResponseEntity<>(
                new ErrorResponse(status.value(), error, message, LocalDateTime.now()),
                status
        );
    }
}