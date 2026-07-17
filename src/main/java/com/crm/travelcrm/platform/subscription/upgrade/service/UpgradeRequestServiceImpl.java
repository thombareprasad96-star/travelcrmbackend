package com.crm.travelcrm.platform.subscription.upgrade.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.cloudinary.CloudinaryService;
import com.crm.travelcrm.common.context.PlatformActor;
import com.crm.travelcrm.common.context.PlatformContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import com.crm.travelcrm.platform.billing.dto.BillingRecordResponse;
import com.crm.travelcrm.platform.billing.entity.BillingRecord;
import com.crm.travelcrm.platform.billing.enums.BillingStatus;
import com.crm.travelcrm.platform.billing.repository.BillingRecordRepository;
import com.crm.travelcrm.platform.billing.service.BillingService;
import com.crm.travelcrm.platform.notification.api.PlatformNotificationType;
import com.crm.travelcrm.platform.notification.api.PlatformNotifyEvent;
import com.crm.travelcrm.platform.payment.entity.PaymentTransaction;
import com.crm.travelcrm.platform.payment.enums.PaymentTransactionStatus;
import com.crm.travelcrm.platform.payment.repository.PaymentTransactionRepository;
import com.crm.travelcrm.platform.subscription.entity.Plan;
import com.crm.travelcrm.platform.subscription.plan.TenantPlanApplier;
import com.crm.travelcrm.platform.subscription.repository.PlanRepository;
import com.crm.travelcrm.platform.subscription.upgrade.dto.CreateUpgradeRequestRequest;
import com.crm.travelcrm.platform.subscription.upgrade.dto.UpgradeRequestResponse;
import com.crm.travelcrm.platform.subscription.upgrade.entity.UpgradeRequest;
import com.crm.travelcrm.platform.subscription.upgrade.enums.OfflinePaymentMode;
import com.crm.travelcrm.platform.subscription.upgrade.enums.PaymentMode;
import com.crm.travelcrm.platform.subscription.upgrade.enums.UpgradeRequestStatus;
import com.crm.travelcrm.platform.subscription.upgrade.repository.UpgradeRequestRepository;
import com.crm.travelcrm.platform.billing.proration.ProrationCalculator;
import com.crm.travelcrm.platform.billing.proration.ProrationResult;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.enums.TenantPlan;
import com.crm.travelcrm.tenent.enums.TenantStatus;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The upgrade-request approval flow. Core principle: a request holds the tenant's upgrade intent in a
 * {@code PENDING} state, the payment settles the linked invoice up-front (online via Razorpay webhook,
 * or offline at approval), and <b>only SuperAdmin approval activates the plan</b> via
 * {@link TenantPlanApplier#applyPlan}. The linked invoice is intentionally issued untagged (see
 * {@link BillingService#issueUpgradeRequestInvoice}) so nothing else applies the plan behind the gate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpgradeRequestServiceImpl implements UpgradeRequestService {

    private final UpgradeRequestRepository upgradeRequestRepository;
    private final TenantRepository tenantRepository;
    private final PlanRepository planRepository;
    private final ProrationCalculator prorationCalculator;
    private final BillingService billingService;
    private final BillingRecordRepository billingRecordRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final TenantPlanApplier tenantPlanApplier;
    private final PlatformAuditRecorder platformAuditRecorder;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final CloudinaryService cloudinaryService;

    // ── Tenant-facing ───────────────────────────────────────────────────────────

    @Override
    @Transactional
    public UpgradeRequestResponse create(Long tenantId, String requestedByEmail, CreateUpgradeRequestRequest request) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BusinessException("Your organization could not be found.", HttpStatus.FORBIDDEN));
        if (!tenant.isOperational()) {
            throw new BusinessException("Your account is not active. Contact support to change your plan.",
                    HttpStatus.FORBIDDEN);
        }

        TenantPlan targetPlan = request.getRequestedPlan();
        Plan targetPlanEntity = planRepository.findByCode(targetPlan)
                .orElseThrow(() -> new BusinessException("Unknown plan: " + targetPlan, HttpStatus.BAD_REQUEST));
        if (!targetPlanEntity.isActive()) {
            throw new BusinessException("That plan is not available.", HttpStatus.BAD_REQUEST);
        }
        if (tenant.getPlan() == targetPlan) {
            throw new BusinessException(
                    "You are already on the " + targetPlanEntity.getDisplayName() + " plan.", HttpStatus.BAD_REQUEST);
        }

        Plan currentPlanEntity = planRepository.findByCode(tenant.getPlan()).orElse(null);
        BigDecimal currentPrice = currentPlanEntity != null && currentPlanEntity.getMonthlyPrice() != null
                ? currentPlanEntity.getMonthlyPrice() : BigDecimal.ZERO;
        BigDecimal targetPrice = targetPlanEntity.getMonthlyPrice() != null
                ? targetPlanEntity.getMonthlyPrice() : BigDecimal.ZERO;

        // Upgrades only — downgrades / lateral changes keep the existing instant self-serve path.
        if (targetPrice.compareTo(currentPrice) <= 0) {
            throw new BusinessException(
                    "This is not an upgrade. Downgrades and lateral changes apply immediately via Change plan.",
                    HttpStatus.BAD_REQUEST);
        }
        // Cross-currency plan moves are not nettable — route to support.
        boolean currencyMismatch = currentPlanEntity != null
                && currentPlanEntity.getCurrency() != null && targetPlanEntity.getCurrency() != null
                && !currentPlanEntity.getCurrency().equalsIgnoreCase(targetPlanEntity.getCurrency());
        if (currencyMismatch) {
            throw new BusinessException(
                    "This plan is billed in a different currency. Please contact support to switch.",
                    HttpStatus.BAD_REQUEST);
        }

        // One live PENDING request at a time.
        if (!upgradeRequestRepository
                .findByTenantIdAndStatusAndDeletedAtIsNull(tenantId, UpgradeRequestStatus.PENDING).isEmpty()) {
            throw new BusinessException(
                    "You already have a pending upgrade request. Cancel it before submitting a new one.",
                    HttpStatus.CONFLICT);
        }

        PaymentMode paymentMode = request.getPaymentMode();
        OfflinePaymentMode offlineMode = null;
        String offlineReference = null;
        String offlineNotes = null;
        if (paymentMode == PaymentMode.OFFLINE) {
            offlineMode = request.getOfflineMode();
            offlineReference = request.getOfflineReference() != null ? request.getOfflineReference().trim() : null;
            offlineNotes = request.getOfflineNotes();
            if (offlineMode == null) {
                throw new BusinessException("Select how you paid (bank transfer / cheque / cash).",
                        HttpStatus.BAD_REQUEST);
            }
            if (!StringUtils.hasText(offlineReference)) {
                throw new BusinessException("A payment reference is required for offline payment.",
                        HttpStatus.BAD_REQUEST);
            }
        }

        // Amount: prorated remainder for an ACTIVE (paying) tenant; full month price otherwise.
        LocalDate today = LocalDate.now();
        LocalDate periodStart = today.withDayOfMonth(1);
        LocalDate periodEnd = YearMonth.from(today).atEndOfMonth();
        BigDecimal amount;
        if (tenant.getStatus() == TenantStatus.ACTIVE) {
            ProrationResult pr = prorationCalculator.calculate(currentPrice, targetPrice, today, periodStart, periodEnd);
            // Last-day edge: proration collapses to ~0 — charge the full month rather than upgrade for free.
            amount = pr.isCharge() ? pr.amount() : targetPrice;
        } else {
            amount = targetPrice;
        }
        String currency = StringUtils.hasText(targetPlanEntity.getCurrency()) ? targetPlanEntity.getCurrency() : "INR";

        // Issue the payable invoice up-front (untagged, no due date). The tenant pays it (online) or the
        // SuperAdmin marks it paid at approval (offline).
        String invoiceNotes = "Plan upgrade " + tenant.getPlan() + " → " + targetPlan + " (pending SuperAdmin approval).";
        BillingRecordResponse invoice = billingService.issueUpgradeRequestInvoice(
                tenantId, targetPlan, amount, periodStart, periodEnd, invoiceNotes);
        Long invoiceId = billingRecordRepository.findByPublicIdAndDeletedAtIsNull(invoice.getPublicId())
                .map(BillingRecord::getId).orElse(null);

        UpgradeRequest ur = UpgradeRequest.builder()
                .tenantId(tenant.getId())
                .tenantCode(tenant.getOrganizationCode())
                .tenantName(tenant.getOrganizationName())
                .currentPlan(tenant.getPlan())
                .requestedPlan(targetPlan)
                .status(UpgradeRequestStatus.PENDING)
                .paymentMode(paymentMode)
                .amount(amount)
                .currency(currency)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .offlineMode(offlineMode)
                .offlineReference(offlineReference)
                .offlineNotes(offlineNotes)
                .billingRecordId(invoiceId)
                .billingRecordPublicId(invoice.getPublicId())
                .requestedByEmail(requestedByEmail)
                .build();
        UpgradeRequest saved = upgradeRequestRepository.save(ur);

        platformAuditRecorder.safeRecord(PlatformAuditAction.UPGRADE_REQUEST_CREATE, true,
                tenant.getId(), tenant.getOrganizationCode(), "UPGRADE_REQUEST", saved.getPublicId(),
                "Upgrade request " + tenant.getPlan() + " → " + targetPlan + " (" + paymentMode + ") by " + requestedByEmail);

        // Offline is the blocking case: the money is already in and only approval activates the plan.
        String paymentDetail = paymentMode == PaymentMode.OFFLINE
                ? "Paid offline via " + offlineMode + " (ref " + offlineReference + ") — verify, then approve."
                : "Online payment pending; approve once it is confirmed.";
        eventPublisher.publishEvent(PlatformNotifyEvent.builder()
                .type(PlatformNotificationType.UPGRADE_REQUEST_CREATED)
                .title("Upgrade request awaiting approval")
                .message(tenant.getOrganizationName() + " requested " + planName(tenant.getPlan()) + " → "
                        + planName(targetPlan) + " for " + currency + " " + amount + ". " + paymentDetail
                        + " Requested by " + requestedByEmail + ".")
                .referenceType(PlatformNotificationType.REF_UPGRADE_REQUEST)
                .referencePublicId(saved.getPublicId())
                .tenantId(tenant.getId())
                .tenantName(tenant.getOrganizationName())
                .tenantPublicId(tenant.getPublicId())
                .build());

        return toResponse(saved);
    }

    @Override
    @Transactional
    public UpgradeRequestResponse uploadProof(Long tenantId, UUID publicId, MultipartFile file) {
        UpgradeRequest ur = requireOwned(tenantId, publicId);
        if (ur.getStatus() != UpgradeRequestStatus.PENDING) {
            throw new BusinessException("Proof can only be added while the request is pending.", HttpStatus.CONFLICT);
        }
        if (ur.getPaymentMode() != PaymentMode.OFFLINE) {
            throw new BusinessException("Proof upload applies to offline payments only.", HttpStatus.BAD_REQUEST);
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException("No file provided.", HttpStatus.BAD_REQUEST);
        }
        ur.setOfflineProofUrl(cloudinaryService.uploadImage(file, "upgrade-proofs"));
        return toResponse(upgradeRequestRepository.save(ur));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UpgradeRequestResponse> listForTenant(Long tenantId) {
        return upgradeRequestRepository.findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(tenantId)
                .stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public UpgradeRequestResponse cancel(Long tenantId, UUID publicId) {
        UpgradeRequest ur = requireOwned(tenantId, publicId);
        if (ur.getStatus() != UpgradeRequestStatus.PENDING) {
            throw new BusinessException("Only a pending request can be cancelled.", HttpStatus.CONFLICT);
        }
        BillingRecord invoice = linkedInvoice(ur);
        if (invoice != null && invoice.getStatus() == BillingStatus.PAID) {
            throw new BusinessException(
                    "Payment has already been received for this request; it can no longer be cancelled. Contact support.",
                    HttpStatus.CONFLICT);
        }
        if (invoice != null && invoice.getStatus() == BillingStatus.UNPAID) {
            billingService.voidInvoice(invoice.getPublicId());
        }
        ur.setStatus(UpgradeRequestStatus.CANCELLED);
        UpgradeRequest saved = upgradeRequestRepository.save(ur);

        platformAuditRecorder.safeRecord(PlatformAuditAction.UPGRADE_REQUEST_CANCEL, true,
                ur.getTenantId(), ur.getTenantCode(), "UPGRADE_REQUEST", saved.getPublicId(),
                "Cancelled upgrade request " + ur.getCurrentPlan() + " → " + ur.getRequestedPlan());
        return toResponse(saved);
    }

    // ── SuperAdmin-facing ───────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<UpgradeRequestResponse> listForAdmin(UpgradeRequestStatus statusFilter) {
        List<UpgradeRequest> rows = statusFilter != null
                ? upgradeRequestRepository.findByStatusAndDeletedAtIsNullOrderByCreatedAtDesc(statusFilter)
                : upgradeRequestRepository.findByDeletedAtIsNullOrderByCreatedAtDesc();
        return rows.stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long pendingCount() {
        return upgradeRequestRepository.countByStatusAndDeletedAtIsNull(UpgradeRequestStatus.PENDING);
    }

    @Override
    @Transactional
    public UpgradeRequestResponse approve(UUID publicId) {
        UpgradeRequest ur = requireRequest(publicId);
        if (ur.getStatus() != UpgradeRequestStatus.PENDING) {
            throw new BusinessException("Only a pending request can be approved.", HttpStatus.CONFLICT);
        }
        Tenant tenant = tenantRepository.findById(ur.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found for request " + publicId));
        BillingRecord invoice = linkedInvoice(ur);

        if (ur.getPaymentMode() == PaymentMode.ONLINE) {
            // Online: the tenant must have already paid (webhook set the invoice PAID) before approval.
            if (invoice == null || invoice.getStatus() != BillingStatus.PAID) {
                throw new BusinessException(
                        "Payment is not yet confirmed. The tenant must complete the online payment before approval.",
                        HttpStatus.CONFLICT);
            }
        } else {
            // Offline: approving IS the verification — settle the invoice now. markPaid also runs the
            // dunning reactivation; it does NOT apply the plan (invoice is untagged), so no double-apply.
            if (invoice != null && invoice.getStatus() == BillingStatus.UNPAID) {
                billingService.markPaid(invoice.getPublicId());
            }
        }

        // THE activation gate: apply the requested plan's entitlements (modules + limits).
        tenantPlanApplier.applyPlan(tenant.getId(), ur.getRequestedPlan(),
                "Upgrade request approved: " + ur.getCurrentPlan() + " → " + ur.getRequestedPlan());

        ur.setStatus(UpgradeRequestStatus.APPROVED);
        stampReviewer(ur);
        UpgradeRequest saved = upgradeRequestRepository.save(ur);

        platformAuditRecorder.safeRecord(PlatformAuditAction.UPGRADE_REQUEST_APPROVE, true,
                tenant.getId(), tenant.getOrganizationCode(), "UPGRADE_REQUEST", saved.getPublicId(),
                "Approved upgrade " + ur.getCurrentPlan() + " → " + ur.getRequestedPlan() + " (" + ur.getPaymentMode() + ")");

        notifyTenantAdmins(tenant, "UPGRADE_REQUEST_APPROVED", saved.getPublicId(), "Plan upgraded",
                "Your upgrade to the " + planName(ur.getRequestedPlan())
                        + " plan was approved and is now active. Enjoy the new features!");

        return toResponse(saved);
    }

    @Override
    @Transactional
    public UpgradeRequestResponse reject(UUID publicId, String reason) {
        UpgradeRequest ur = requireRequest(publicId);
        if (ur.getStatus() != UpgradeRequestStatus.PENDING) {
            throw new BusinessException("Only a pending request can be rejected.", HttpStatus.CONFLICT);
        }
        Tenant tenant = tenantRepository.findById(ur.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found for request " + publicId));
        BillingRecord invoice = linkedInvoice(ur);
        boolean alreadyPaid = invoice != null && invoice.getStatus() == BillingStatus.PAID;

        // Void an unpaid invoice so it doesn't linger on the ledger. A paid (online) one is left settled
        // and refunded out-of-band — there is no automated refund path (product decision).
        if (invoice != null && invoice.getStatus() == BillingStatus.UNPAID) {
            billingService.voidInvoice(invoice.getPublicId());
        }

        ur.setStatus(UpgradeRequestStatus.REJECTED);
        ur.setDecisionNote(reason);
        stampReviewer(ur);
        UpgradeRequest saved = upgradeRequestRepository.save(ur);

        platformAuditRecorder.safeRecord(PlatformAuditAction.UPGRADE_REQUEST_REJECT, true,
                tenant.getId(), tenant.getOrganizationCode(), "UPGRADE_REQUEST", saved.getPublicId(),
                "Rejected upgrade " + ur.getCurrentPlan() + " → " + ur.getRequestedPlan() + ": " + reason
                        + (alreadyPaid ? " (payment received — refund pending)" : ""));

        notifyTenantAdmins(tenant, "UPGRADE_REQUEST_REJECTED", saved.getPublicId(), "Upgrade request declined",
                "Your upgrade to the " + planName(ur.getRequestedPlan()) + " plan was not approved. Reason: " + reason
                        + (alreadyPaid ? " Your payment will be refunded." : ""));

        return toResponse(saved);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UpgradeRequest requireRequest(UUID publicId) {
        return upgradeRequestRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Upgrade request not found: " + publicId));
    }

    /** Object-level ownership: a foreign request 404s, never leaks. */
    private UpgradeRequest requireOwned(Long tenantId, UUID publicId) {
        UpgradeRequest ur = requireRequest(publicId);
        if (!Objects.equals(ur.getTenantId(), tenantId)) {
            throw new ResourceNotFoundException("Upgrade request not found: " + publicId);
        }
        return ur;
    }

    private BillingRecord linkedInvoice(UpgradeRequest ur) {
        return ur.getBillingRecordPublicId() != null
                ? billingRecordRepository.findByPublicIdAndDeletedAtIsNull(ur.getBillingRecordPublicId()).orElse(null)
                : null;
    }

    private void stampReviewer(UpgradeRequest ur) {
        PlatformActor actor = PlatformContext.getActor();
        ur.setReviewedByEmail(actor != null ? actor.email() : null);
        ur.setReviewedAt(LocalDateTime.now());
    }

    private String planName(TenantPlan plan) {
        if (plan == null) {
            return "";
        }
        return planRepository.findByCode(plan).map(Plan::getDisplayName).orElse(plan.name());
    }

    private void notifyTenantAdmins(Tenant tenant, String type, UUID referencePublicId, String title, String message) {
        List<Long> recipients = userRepository
                .findByTenantIdAndRoleInAndIsActiveTrue(tenant.getId(), List.of("TENANT_ADMIN", "MANAGER"))
                .stream().map(User::getId).toList();
        if (recipients.isEmpty()) {
            return;
        }
        eventPublisher.publishEvent(NotifyEvent.builder()
                .type(type)
                .tenantId(tenant.getId())
                .recipientUserIds(recipients)
                .title(title)
                .message(message)
                .referenceType("UPGRADE_REQUEST")
                .referencePublicId(referencePublicId)
                .channels(Set.of(DeliveryChannel.IN_APP))
                .build());
    }

    private UpgradeRequestResponse toResponse(UpgradeRequest ur) {
        BillingRecord invoice = linkedInvoice(ur);
        BillingStatus invoiceStatus = invoice != null ? invoice.getStatus() : null;

        PaymentTransactionStatus onlineStatus = null;
        if (ur.getPaymentMode() == PaymentMode.ONLINE && ur.getBillingRecordId() != null) {
            onlineStatus = paymentTransactionRepository.findByTenantIdOrderByCreatedAtDesc(ur.getTenantId())
                    .stream()
                    .filter(t -> Objects.equals(t.getBillingRecordId(), ur.getBillingRecordId()))
                    .map(PaymentTransaction::getStatus)
                    .findFirst().orElse(null);
        }

        return UpgradeRequestResponse.builder()
                .publicId(ur.getPublicId())
                .tenantCode(ur.getTenantCode())
                .tenantName(ur.getTenantName())
                .currentPlan(ur.getCurrentPlan())
                .currentPlanName(planName(ur.getCurrentPlan()))
                .requestedPlan(ur.getRequestedPlan())
                .requestedPlanName(planName(ur.getRequestedPlan()))
                .status(ur.getStatus())
                .paymentMode(ur.getPaymentMode())
                .amount(ur.getAmount())
                .currency(ur.getCurrency())
                .periodStart(ur.getPeriodStart())
                .periodEnd(ur.getPeriodEnd())
                .offlineMode(ur.getOfflineMode())
                .offlineReference(ur.getOfflineReference())
                .offlineNotes(ur.getOfflineNotes())
                .offlineProofUrl(ur.getOfflineProofUrl())
                .invoicePublicId(ur.getBillingRecordPublicId())
                .invoiceNumber(invoice != null ? invoice.getInvoiceNumber() : null)
                .invoiceStatus(invoiceStatus)
                .paymentConfirmed(invoiceStatus == BillingStatus.PAID)
                .onlinePaymentStatus(onlineStatus)
                .requestedByEmail(ur.getRequestedByEmail())
                .reviewedByEmail(ur.getReviewedByEmail())
                .reviewedAt(ur.getReviewedAt())
                .decisionNote(ur.getDecisionNote())
                .createdAt(ur.getCreatedAt())
                .build();
    }
}