package com.crm.travelcrm.lead.exception;

import com.crm.travelcrm.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * The tenant's plan lead cap rejected a creation.
 *
 * <p>Extends {@link BusinessException} with FORBIDDEN so the HUMAN path is unchanged — a user still
 * gets the same 403 and the same message. It exists as its own type only so the INBOUND path can tell
 * "the plan is full" apart from every other business failure without catching a generic supertype and
 * silently swallowing unrelated bugs.
 *
 * <p>The inbound path must NOT propagate this as a 403 (owner decision 9). A 4xx to JustDial means
 * retries and eventually a disabled integration — losing the lead to protect a counter. Instead the
 * delivery is recorded {@code QUARANTINED_QUOTA}, the tenant is notified, and the provider gets a 200.
 */
public class LeadQuotaExceededException extends BusinessException {

    public LeadQuotaExceededException(String message) {
        super(message, HttpStatus.FORBIDDEN);
    }
}
