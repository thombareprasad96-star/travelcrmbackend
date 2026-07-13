package com.crm.travelcrm.platform.subscription.self.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Result of a tenant self-serve plan change.
 *
 * <p><b>Upgrade (pay-first):</b> {@code pendingPayment=true} — the plan has NOT changed yet; the client
 * must pay {@code prorationInvoicePublicId} ({@code amountDue}) via
 * {@code POST /api/company/billing/{id}/pay-intent}, and the upgrade applies automatically once the
 * gateway confirms the payment.
 *
 * <p><b>Downgrade / lateral / already-applied:</b> {@code pendingPayment=false} — the plan changed
 * immediately. A downgrade issues a credit note (nothing to pay); an immediate change may still surface
 * a prorated top-up to pay when applicable.
 */
@Value
@Builder
public class MyPlanChangeResponse {
    String planCode;
    String planDisplayName;
    String status;
    /** True when the plan change is gated on paying {@code prorationInvoicePublicId} (pay-first upgrade). */
    boolean pendingPayment;
    UUID prorationInvoicePublicId;
    BigDecimal amountDue;
    String currency;
    String message;
}