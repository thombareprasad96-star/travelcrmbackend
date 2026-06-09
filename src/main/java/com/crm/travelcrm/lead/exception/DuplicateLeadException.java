package com.crm.travelcrm.lead.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateLeadException extends RuntimeException {
    public DuplicateLeadException(String message) {
        super(message);
    }
}
