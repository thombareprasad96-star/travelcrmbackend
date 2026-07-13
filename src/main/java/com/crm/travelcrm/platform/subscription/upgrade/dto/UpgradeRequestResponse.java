package com.crm.travelcrm.platform.subscription.upgrade.dto;

import com.crm.travelcrm.platform.billing.enums.BillingStatus;
import com.crm.travelcrm.platform.payment.enums.PaymentTransactionStatus;
import com.crm.travelcrm.platform.subscription.upgrade.enums.OfflinePaymentMode;
import com.crm.travelcrm.platform.subscription.upgrade.enums.PaymentMode;
import com.crm.travelcrm.platform.subscription.upgrade.enums.UpgradeRequestStatus;
import com.crm.travelcrm.tenent.enums.TenantPlan;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Upgrade-request view. Externalises {@code publicId} only. Payment fields are DERIVED from the linked
 * invoice / payment transaction at read time (single source of truth — no duplicated status on the
 * request row).
 */
@Getter
@Builder
public class UpgradeRequestResponse {

    private UUID publicId;
    private String tenantCode;
    private String tenantName;

    private TenantPlan currentPlan;
    private String currentPlanName;
    private TenantPlan requestedPlan;
    private String requestedPlanName;

    private UpgradeRequestStatus status;
    private PaymentMode paymentMode;
    private BigDecimal amount;
    private String currency;
    private LocalDate periodStart;
    private LocalDate periodEnd;

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
    /** Latest online payment-attempt status for the linked invoice, if any (ONLINE only). */
    private PaymentTransactionStatus onlinePaymentStatus;

    private String requestedByEmail;
    private String reviewedByEmail;
    private LocalDateTime reviewedAt;
    private String decisionNote;
    private LocalDateTime createdAt;
}