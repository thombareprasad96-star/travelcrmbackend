package com.crm.travelcrm.platform.subscription.upgrade.dto;

import com.crm.travelcrm.platform.subscription.upgrade.enums.OfflinePaymentMode;
import com.crm.travelcrm.platform.subscription.upgrade.enums.PaymentMode;
import com.crm.travelcrm.tenent.enums.TenantPlan;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Tenant admin submits a plan-upgrade request. When {@code paymentMode == OFFLINE}, {@code offlineMode}
 * and {@code offlineReference} are required (validated in the service); the proof is uploaded
 * separately via {@code POST /{publicId}/proof}.
 */
@Data
public class CreateUpgradeRequestRequest {

    @NotNull(message = "requestedPlan is required")
    private TenantPlan requestedPlan;

    @NotNull(message = "paymentMode is required")
    private PaymentMode paymentMode;

    /** Required when paymentMode == OFFLINE. */
    private OfflinePaymentMode offlineMode;

    @Size(max = 120, message = "Reference cannot exceed 120 characters")
    private String offlineReference;

    @Size(max = 500, message = "Notes cannot exceed 500 characters")
    private String offlineNotes;
}