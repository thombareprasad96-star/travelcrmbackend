package com.crm.travelcrm.subagent.license.dto;

import com.crm.travelcrm.platform.subscription.upgrade.enums.OfflinePaymentMode;
import com.crm.travelcrm.platform.subscription.upgrade.enums.PaymentMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

/**
 * Tenant opens (or re-opens, after a rejection) a seat-license purchase for an EXISTING
 * {@code PENDING_LICENSE} sub-agent. Used by the resubmit-payment path; the first request is created
 * automatically when the sub-agent is provisioned over cap. When {@code paymentMode == OFFLINE},
 * {@code offlineMode} and {@code offlineReference} are required (validated in the service).
 */
@Data
public class OpenSubAgentLicenseRequest {

    @NotNull(message = "subAgentPublicId is required")
    private UUID subAgentPublicId;

    @NotNull(message = "paymentMode is required")
    private PaymentMode paymentMode;

    /** Required when paymentMode == OFFLINE. */
    private OfflinePaymentMode offlineMode;

    @Size(max = 120, message = "Reference cannot exceed 120 characters")
    private String offlineReference;

    @Size(max = 500, message = "Notes cannot exceed 500 characters")
    private String offlineNotes;
}