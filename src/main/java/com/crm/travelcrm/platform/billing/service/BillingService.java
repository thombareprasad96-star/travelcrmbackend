package com.crm.travelcrm.platform.billing.service;

import com.crm.travelcrm.platform.billing.dto.BillingRecordResponse;
import com.crm.travelcrm.platform.billing.dto.CreateBillingRequest;
import com.crm.travelcrm.platform.billing.dto.SeatFeeResponse;
import com.crm.travelcrm.platform.billing.dto.UpdateSeatFeeRequest;
import com.crm.travelcrm.tenent.enums.TenantPlan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Platform billing (SuperAdmin → tenant invoices). */
public interface BillingService {

    List<BillingRecordResponse> listForTenant(UUID tenantPublicId);

    BillingRecordResponse create(UUID tenantPublicId, CreateBillingRequest request);

    BillingRecordResponse markPaid(UUID billingPublicId);

    BillingRecordResponse markUnpaid(UUID billingPublicId);

    /**
     * Issue a mid-cycle plan-change proration line. A positive {@code signedAmount} becomes an UNPAID
     * top-up invoice (upgrade); a negative amount becomes a {@code CREDIT} note (downgrade). Tenant is
     * resolved by internal id (this is called from {@code changePlan}, which already loaded it).
     */
    BillingRecordResponse issueProration(Long tenantId, TenantPlan plan, BigDecimal signedAmount,
                                         LocalDate periodStart, LocalDate periodEnd, String notes);

    /**
     * Issue a pay-first plan-upgrade charge: an UNPAID invoice tagged with {@code targetPlan}. The plan
     * is NOT changed here — settling this invoice (webhook capture or offline mark-paid) applies the
     * upgrade. {@code amount} must be positive (the prorated top-up).
     */
    BillingRecordResponse issuePlanUpgradeCharge(Long tenantId, TenantPlan targetPlan, BigDecimal amount,
                                                 LocalDate periodStart, LocalDate periodEnd, String notes);

    /** Void any outstanding (UNPAID) pay-first upgrade invoices for the tenant. Returns the count voided. */
    int voidPendingPlanUpgrades(Long tenantId);

    /**
     * Issue the payable invoice for a tenant-initiated plan-UPGRADE request. Unlike
     * {@link #issuePlanUpgradeCharge}, this invoice carries NO {@code upgradeToPlan} tag — settling it
     * must NOT auto-apply the plan, because SuperAdmin approval is the activation gate — and NO due
     * date, so it never trips dunning while the request sits in review. {@code amount} must be positive.
     */
    BillingRecordResponse issueUpgradeRequestInvoice(Long tenantId, TenantPlan plan, BigDecimal amount,
                                                     LocalDate periodStart, LocalDate periodEnd, String notes);

    /**
     * Void an outstanding (non-PAID) invoice — used when a plan-upgrade request is rejected or
     * cancelled so its unpaid invoice does not linger on the tenant's ledger. Throws if already PAID.
     */
    BillingRecordResponse voidInvoice(UUID billingPublicId);

    /** The tenant's current sub-agent seat-fee position (effective rate, active seats, monthly total). */
    SeatFeeResponse getSeatFee(UUID tenantPublicId);

    /**
     * Set (or clear, when {@code monthlySeatFee} is null) the tenant's per-tenant sub-agent seat-fee
     * override. Independent of the quota-override pin. Returns the resulting seat-fee position.
     */
    SeatFeeResponse setSeatFee(UUID tenantPublicId, UpdateSeatFeeRequest request);
}