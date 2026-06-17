package com.crm.travelcrm.customer.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/**
 * Thrown when a customer cannot be resolved within the current tenant scope.
 * Mapped to HTTP 404 by {@code GlobalExceptionHandler}.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class CustomerNotFoundException extends RuntimeException {

    public CustomerNotFoundException(String message) {
        super(message);
    }

    public CustomerNotFoundException(UUID publicId) {
        super("Customer not found: " + publicId);
    }
}