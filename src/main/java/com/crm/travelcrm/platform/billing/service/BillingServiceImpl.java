package com.crm.travelcrm.platform.billing.service;

import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import com.crm.travelcrm.platform.billing.dto.BillingRecordResponse;
import com.crm.travelcrm.platform.billing.dto.CreateBillingRequest;
import com.crm.travelcrm.platform.billing.dto.SeatFeeResponse;
import com.crm.travelcrm.platform.billing.dto.UpdateSeatFeeRequest;
import com.crm.travelcrm.platform.billing.entity.BillingRecord;
import com.crm.travelcrm.platform.billing.enums.BillingStatus;
import com.crm.travelcrm.platform.billing.repository.BillingRecordRepository;
import com.crm.travelcrm.platform.subscription.dunning.DunningService;
import com.crm.travelcrm.platform.subscription.entity.Plan;
import com.crm.travelcrm.platform.subscription.plan.TenantPlanApplier;
import com.crm.travelcrm.platform.subscription.repository.PlanRepository;
import com.crm.travelcrm.subagent.service.SubAgentSeatFeeService;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.enums.TenantPlan;
import com.crm.travelcrm.tenent.exception.TenantNotFoundException;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BillingServiceImpl implements BillingService {

    private final BillingRecordRepository billingRepository;
    private final TenantRepository tenantRepository;
    private final PlanRepository planRepository;
    private final PlatformAuditRecorder platformAuditRecorder;
    /** Offline settlement must reactivate a dunning-suspended tenant, exactly like the webhook path. */
    private final DunningService dunningService;
    /** Applies a pay-first upgrade when its tagged invoice is settled offline. */
    private final TenantPlanApplier tenantPlanApplier;
    /** Resolves the per-tenant sub-agent seat fee folded into the recurring monthly invoice. */
    private final SubAgentSeatFeeService seatFeeService;

    @Override
    @Transactional(readOnly = true)
    public List<BillingRecordResponse> listForTenant(UUID tenantPublicId) {
        // History view: resolve even a soft-deleted tenant so its past invoices remain visible.
        Tenant tenant = tenantRepository.findByPublicId(tenantPublicId)
                .orElseThrow(() -> new TenantNotFoundException(tenantPublicId));
        return billingRepository
                .findByTenantIdAndDeletedAtIsNullOrderByIssueDateDescIdDesc(tenant.getId())
                .stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public BillingRecordResponse create(UUID tenantPublicId, CreateBillingRequest request) {
        // Issue only against a live tenant.
        Tenant tenant = tenantRepository.findByPublicIdAndDeletedAtIsNull(tenantPublicId)
                .orElseThrow(() -> new TenantNotFoundException(tenantPublicId));

        TenantPlan planCode = request.getPlan() != null ? request.getPlan() : tenant.getPlan();
        Plan plan = planRepository.findByCode(planCode).orElse(null);

        // An explicit amount is taken verbatim (the SuperAdmin controls the total). Otherwise this is
        // the recurring plan charge, onto which the current sub-agent seat fee is folded.
        boolean planDerived = request.getAmount() == null;
        BigDecimal amount = planDerived
                ? (plan != null && plan.getMonthlyPrice() != null ? plan.getMonthlyPrice() : BigDecimal.ZERO)
                : request.getAmount();
        String currency = StringUtils.hasText(request.getCurrency())
                ? request.getCurrency()
                : (plan != null && StringUtils.hasText(plan.getCurrency()) ? plan.getCurrency() : "INR");

        String notes = request.getNotes();
        if (planDerived) {
            SubAgentSeatFeeService.SeatFeeQuote seatFee = seatFeeService.quote(tenant);
            if (seatFee.total().signum() > 0) {
                amount = amount.add(seatFee.total());
                String seatLine = seatFee.activeSeats() + " sub-agent seat(s) @ " + currency + " "
                        + seatFee.rate().stripTrailingZeros().toPlainString() + " = " + currency + " "
                        + seatFee.total().stripTrailingZeros().toPlainString();
                notes = StringUtils.hasText(notes) ? notes + " | " + seatLine : "Includes " + seatLine;
            }
        }

        LocalDate today = LocalDate.now();
        LocalDate periodStart = request.getPeriodStart() != null
                ? request.getPeriodStart() : today.withDayOfMonth(1);
        LocalDate periodEnd = request.getPeriodEnd() != null
                ? request.getPeriodEnd() : YearMonth.from(today).atEndOfMonth();
        LocalDate dueDate = request.getDueDate() != null
                ? request.getDueDate() : today.plusDays(15);

        BillingRecord record = BillingRecord.builder()
                .tenantId(tenant.getId())
                .tenantCode(tenant.getOrganizationCode())
                .tenantName(tenant.getOrganizationName())
                .invoiceNumber(nextInvoiceNumber())
                .plan(planCode)
                .amount(amount)
                .currency(currency)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .issueDate(today)
                .dueDate(dueDate)
                .status(BillingStatus.UNPAID)
                .notes(notes)
                .build();

        BillingRecord saved = billingRepository.save(record);
        audit(PlatformAuditAction.BILLING_ISSUE, saved,
                "Issued " + saved.getInvoiceNumber() + " (" + currency + " " + amount
                        + ") to " + tenant.getOrganizationName());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public BillingRecordResponse markPaid(UUID billingPublicId) {
        BillingRecord record = requireRecord(billingPublicId);
        BillingStatus prevStatus = record.getStatus();
        record.setStatus(BillingStatus.PAID);
        record.setPaidDate(LocalDate.now());
        BillingRecord saved = billingRepository.save(record);
        audit(PlatformAuditAction.BILLING_MARK_PAID, saved,
                "Marked " + saved.getInvoiceNumber() + " paid");
        // Settling an unpaid receivable offline must reactivate a PAST_DUE/EXPIRED tenant and advance
        // access, mirroring the webhook capture path — otherwise a tenant that paid by bank transfer
        // stays permanently locked out. onInvoicePaid no-ops when reactivation does not apply.
        if (prevStatus == BillingStatus.UNPAID) {
            dunningService.onInvoicePaid(saved.getTenantId(), saved.getPeriodEnd());
            // Pay-first upgrade: settling the tagged invoice offline applies the plan, like the webhook.
            if (saved.getUpgradeToPlan() != null) {
                tenantPlanApplier.applyPlan(saved.getTenantId(), saved.getUpgradeToPlan(),
                        "Upgraded to " + saved.getUpgradeToPlan() + " on payment of " + saved.getInvoiceNumber());
            }
        }
        return toResponse(saved);
    }

    @Override
    @Transactional
    public BillingRecordResponse markUnpaid(UUID billingPublicId) {
        BillingRecord record = requireRecord(billingPublicId);
        record.setStatus(BillingStatus.UNPAID);
        record.setPaidDate(null);
        BillingRecord saved = billingRepository.save(record);
        audit(PlatformAuditAction.BILLING_MARK_UNPAID, saved,
                "Marked " + saved.getInvoiceNumber() + " unpaid");
        return toResponse(saved);
    }

    @Override
    @Transactional
    public BillingRecordResponse issueProration(Long tenantId, TenantPlan plan, BigDecimal signedAmount,
                                                LocalDate periodStart, LocalDate periodEnd, String notes) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant not found: " + tenantId));

        Plan planEntity = planRepository.findByCode(plan).orElse(null);
        String currency = planEntity != null && StringUtils.hasText(planEntity.getCurrency())
                ? planEntity.getCurrency() : "INR";
        boolean credit = signedAmount.signum() < 0;
        LocalDate today = LocalDate.now();

        BillingRecord record = BillingRecord.builder()
                .tenantId(tenant.getId())
                .tenantCode(tenant.getOrganizationCode())
                .tenantName(tenant.getOrganizationName())
                .invoiceNumber(nextInvoiceNumber())
                .plan(plan)
                .amount(signedAmount)                 // negative for a credit note
                .currency(currency)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .issueDate(today)
                // A credit note is nothing the tenant owes: no due date, marked settled at issue.
                .dueDate(credit ? null : today.plusDays(15))
                .paidDate(credit ? today : null)
                .status(credit ? BillingStatus.CREDIT : BillingStatus.UNPAID)
                .notes(notes)
                .build();

        BillingRecord saved = billingRepository.save(record);
        audit(PlatformAuditAction.BILLING_ISSUE, saved,
                (credit ? "Issued credit note " : "Issued proration invoice ") + saved.getInvoiceNumber()
                        + " (" + currency + " " + signedAmount + ") to " + tenant.getOrganizationName());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public BillingRecordResponse issuePlanUpgradeCharge(Long tenantId, TenantPlan targetPlan, BigDecimal amount,
                                                        LocalDate periodStart, LocalDate periodEnd, String notes) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant not found: " + tenantId));

        Plan planEntity = planRepository.findByCode(targetPlan).orElse(null);
        String currency = planEntity != null && StringUtils.hasText(planEntity.getCurrency())
                ? planEntity.getCurrency() : "INR";
        LocalDate today = LocalDate.now();

        BillingRecord record = BillingRecord.builder()
                .tenantId(tenant.getId())
                .tenantCode(tenant.getOrganizationCode())
                .tenantName(tenant.getOrganizationName())
                .invoiceNumber(nextInvoiceNumber())
                .plan(targetPlan)
                .amount(amount)
                .currency(currency)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .issueDate(today)
                .dueDate(today.plusDays(15))
                .status(BillingStatus.UNPAID)
                .upgradeToPlan(targetPlan)     // paying this applies the upgrade
                .notes(notes)
                .build();

        BillingRecord saved = billingRepository.save(record);
        audit(PlatformAuditAction.BILLING_ISSUE, saved,
                "Issued pay-first upgrade charge " + saved.getInvoiceNumber() + " (" + currency + " " + amount
                        + ") to " + tenant.getOrganizationName() + " for plan " + targetPlan);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public BillingRecordResponse issueUpgradeRequestInvoice(Long tenantId, TenantPlan plan, BigDecimal amount,
                                                            LocalDate periodStart, LocalDate periodEnd, String notes) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant not found: " + tenantId));

        Plan planEntity = planRepository.findByCode(plan).orElse(null);
        String currency = planEntity != null && StringUtils.hasText(planEntity.getCurrency())
                ? planEntity.getCurrency() : "INR";
        LocalDate today = LocalDate.now();

        BillingRecord record = BillingRecord.builder()
                .tenantId(tenant.getId())
                .tenantCode(tenant.getOrganizationCode())
                .tenantName(tenant.getOrganizationName())
                .invoiceNumber(nextInvoiceNumber())
                .plan(plan)
                .amount(amount)
                .currency(currency)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .issueDate(today)
                .dueDate(null)                 // discretionary upgrade — NOT a dunning receivable
                .status(BillingStatus.UNPAID)
                // NO upgradeToPlan tag: approval (not payment) applies the plan.
                .notes(notes)
                .build();

        BillingRecord saved = billingRepository.save(record);
        audit(PlatformAuditAction.BILLING_ISSUE, saved,
                "Issued plan-upgrade request charge " + saved.getInvoiceNumber() + " (" + currency + " " + amount
                        + ") to " + tenant.getOrganizationName() + " for plan " + plan);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public BillingRecordResponse voidInvoice(UUID billingPublicId) {
        BillingRecord record = requireRecord(billingPublicId);
        if (record.getStatus() == BillingStatus.PAID) {
            throw new BusinessException("A paid invoice cannot be voided.");
        }
        record.setStatus(BillingStatus.VOID);
        BillingRecord saved = billingRepository.save(record);
        audit(PlatformAuditAction.BILLING_VOID, saved, "Voided " + saved.getInvoiceNumber());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public int voidPendingPlanUpgrades(Long tenantId) {
        List<BillingRecord> pending = billingRepository
                .findByTenantIdAndUpgradeToPlanIsNotNullAndStatusAndDeletedAtIsNull(tenantId, BillingStatus.UNPAID);
        for (BillingRecord r : pending) {
            r.setStatus(BillingStatus.VOID);
            billingRepository.save(r);
        }
        return pending.size();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private BillingRecord requireRecord(UUID publicId) {
        return billingRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + publicId));
    }

    @Override
    @Transactional(readOnly = true)
    public SeatFeeResponse getSeatFee(UUID tenantPublicId) {
        Tenant tenant = tenantRepository.findByPublicIdAndDeletedAtIsNull(tenantPublicId)
                .orElseThrow(() -> new TenantNotFoundException(tenantPublicId));
        return toSeatFeeResponse(tenant);
    }

    @Override
    @Transactional
    public SeatFeeResponse setSeatFee(UUID tenantPublicId, UpdateSeatFeeRequest request) {
        Tenant tenant = tenantRepository.findByPublicIdAndDeletedAtIsNull(tenantPublicId)
                .orElseThrow(() -> new TenantNotFoundException(tenantPublicId));
        tenant.setSubAgentSeatFee(request.getMonthlySeatFee());   // null clears → platform default
        tenantRepository.save(tenant);
        String detail = request.getMonthlySeatFee() == null
                ? "Cleared sub-agent seat-fee override — reverted to platform default"
                : "Set sub-agent seat-fee override to " + request.getMonthlySeatFee();
        platformAuditRecorder.safeRecord(PlatformAuditAction.QUOTA_OVERRIDE, true,
                tenant.getId(), tenant.getOrganizationCode(), "TENANT", tenant.getPublicId(), detail);
        return toSeatFeeResponse(tenant);
    }

    private SeatFeeResponse toSeatFeeResponse(Tenant tenant) {
        SubAgentSeatFeeService.SeatFeeQuote q = seatFeeService.quote(tenant);
        return SeatFeeResponse.builder()
                .effectiveRate(q.rate())
                .usingPlatformDefault(tenant.getSubAgentSeatFee() == null)
                .overrideRate(tenant.getSubAgentSeatFee())
                .activeSeats(q.activeSeats())
                .monthlyTotal(q.total())
                .build();
    }

    /** Simple monotonic invoice number. Adequate at SuperAdmin (single-issuer) scale; the unique
     *  constraint on {@code invoice_number} guards integrity against the rare concurrent collision. */
    private String nextInvoiceNumber() {
        return String.format("INV-%06d", billingRepository.count() + 1);
    }

    private void audit(PlatformAuditAction action, BillingRecord r, String description) {
        platformAuditRecorder.safeRecord(action, true,
                r.getTenantId(), r.getTenantCode(), "BILLING", r.getPublicId(), description);
    }

    private BillingRecordResponse toResponse(BillingRecord r) {
        return BillingRecordResponse.builder()
                .publicId(r.getPublicId())
                .invoiceNumber(r.getInvoiceNumber())
                .tenantCode(r.getTenantCode())
                .tenantName(r.getTenantName())
                .plan(r.getPlan())
                .amount(r.getAmount())
                .currency(r.getCurrency())
                .periodStart(r.getPeriodStart())
                .periodEnd(r.getPeriodEnd())
                .issueDate(r.getIssueDate())
                .dueDate(r.getDueDate())
                .paidDate(r.getPaidDate())
                .status(r.getStatus())
                .overdue(r.isOverdue())
                .notes(r.getNotes())
                .createdAt(r.getCreatedAt())
                .build();
    }
}