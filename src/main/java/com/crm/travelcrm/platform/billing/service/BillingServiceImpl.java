package com.crm.travelcrm.platform.billing.service;

import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import com.crm.travelcrm.platform.billing.dto.BillingRecordResponse;
import com.crm.travelcrm.platform.billing.dto.CreateBillingRequest;
import com.crm.travelcrm.platform.billing.entity.BillingRecord;
import com.crm.travelcrm.platform.billing.enums.BillingStatus;
import com.crm.travelcrm.platform.billing.repository.BillingRecordRepository;
import com.crm.travelcrm.platform.subscription.entity.Plan;
import com.crm.travelcrm.platform.subscription.repository.PlanRepository;
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

        BigDecimal amount = request.getAmount() != null
                ? request.getAmount()
                : (plan != null && plan.getMonthlyPrice() != null ? plan.getMonthlyPrice() : BigDecimal.ZERO);
        String currency = StringUtils.hasText(request.getCurrency())
                ? request.getCurrency()
                : (plan != null && StringUtils.hasText(plan.getCurrency()) ? plan.getCurrency() : "INR");

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
                .notes(request.getNotes())
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
        record.setStatus(BillingStatus.PAID);
        record.setPaidDate(LocalDate.now());
        BillingRecord saved = billingRepository.save(record);
        audit(PlatformAuditAction.BILLING_MARK_PAID, saved,
                "Marked " + saved.getInvoiceNumber() + " paid");
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

    // ── helpers ───────────────────────────────────────────────────────────────

    private BillingRecord requireRecord(UUID publicId) {
        return billingRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + publicId));
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