package com.crm.travelcrm.customer.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a customer with the same phone number already exists for the tenant.
 * Phone is the natural key the UI uses for "search existing customer", so it must
 * stay unique per tenant. Mapped to HTTP 409 by {@code GlobalExceptionHandler}.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateCustomerException extends RuntimeException {

    public DuplicateCustomerException(String message) {
        super(message);
    }
}