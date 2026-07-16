package com.crm.travelcrm.subagent.license.dto;

import com.crm.travelcrm.platform.billing.enums.BillingStatus;
import com.crm.travelcrm.platform.subscription.upgrade.enums.OfflinePaymentMode;
import com.crm.travelcrm.platform.subscription.upgrade.enums.PaymentMode;
import com.crm.travelcrm.subagent.license.enums.SubAgentLicenseRequestStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sub-agent seat-license request view. Externalises {@code publicId} only. Payment fields are DERIVED
 * from the linked invoice at read time (single source of truth). The tenant pays {@code invoicePublicId}
 * online (Razorpay) or offline; {@code paymentConfirmed} flips once it is PAID.
 */
@Getter
@Builder
public class SubAgentLicenseRequestResponse {

    private UUID publicId;
    private String tenantCode;
    private String tenantName;

    // The pending sub-agent this purchase will activate.
    private UUID subAgentPublicId;
    private String subAgentName;
    private String subAgentEmail;

    private int quantity;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private String currency;

    private SubAgentLicenseRequestStatus status;
    private PaymentMode paymentMode;

    // Offline reference (paymentMode == OFFLINE)
    private OfflinePaymentMode offlineMode;
    private String offlineReference;
    private String offlineNotes;
    private String offlineProofUrl;

    // Linked invoice — the tenant pays it online; the SuperAdmin sees its settlement.
    private UUID invoicePublicId;
    private String invoiceNumber;
    private BillingStatus invoiceStatus;
    /** True once the linked invoice is PAID (online capture or offline mark-paid). */
    private boolean paymentConfirmed;

    private String requestedByEmail;
    private String reviewedByEmail;
    private LocalDateTime reviewedAt;
    private String decisionNote;
    private LocalDateTime createdAt;
}