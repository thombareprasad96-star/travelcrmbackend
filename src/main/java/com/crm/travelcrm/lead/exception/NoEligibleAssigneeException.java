package com.crm.travelcrm.lead.exception;

import com.crm.travelcrm.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * An inbound lead arrived for a tenant with nobody eligible to own it.
 *
 * <p><b>Reachable, not hypothetical:</b> a tenant can deactivate every agent, or scope every user out
 * of LEAD_READ. Every lead must have an owner ({@code leads.assigned_user_id} is NOT NULL), so there
 * is no "unassigned" state to fall back on.
 *
 * <p>The inbound path quarantines on this rather than failing the webhook, for the same reason as
 * {@link LeadQuotaExceededException}: a 5xx to the provider means retries and eventually a disabled
 * integration.
 */
public class NoEligibleAssigneeException extends BusinessException {

    public NoEligibleAssigneeException(Long tenantId) {
        super("No eligible user is available to be assigned this lead (tenant " + tenantId + ").",
                HttpStatus.CONFLICT);
    }
}
