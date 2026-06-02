// ─── LeadNotFoundException.java ───────────────────────────────────────────────
package com.crm.travelcrm.lead.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class LeadNotFoundException extends RuntimeException {
    public LeadNotFoundException(String message) {
        super(message);
    }

    public LeadNotFoundException(Long id) {
        super("Lead not found with ID: " + id);
    }
}