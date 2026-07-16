
package com.crm.travelcrm.subagent.license.service;

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
import com.crm.travelcrm.platform.subscription.upgrade.enums.OfflinePaymentMode;
import com.crm.travelcrm.platform.subscription.upgrade.enums.PaymentMode;
import com.crm.travelcrm.subagent.entity.SubAgentProfile;
import com.crm.travelcrm.subagent.enums.SubAgentStatus;
import com.crm.travelcrm.subagent.license.dto.SubAgentLicenseRequestResponse;
import com.crm.travelcrm.subagent.license.entity.SubAgentLicenseRequest;
import com.crm.travelcrm.subagent.license.enums.SubAgentLicenseRequestStatus;
import com.crm.travelcrm.subagent.license.repository.SubAgentLicenseRequestRepository;
import com.crm.travelcrm.subagent.repository.SubAgentProfileRepository;
import com.crm.travelcrm.subagent.service.SubAgentSeatFeeService;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The sub-agent (Travel Partner) seat-license flow — mirrors {@code UpgradeRequestServiceImpl} for the
 * seat add-on. Core principle: a request holds the purchase {@code PENDING}, the payment settles the
 * linked invoice up-front, and <b>only SuperAdmin approval grants the seats</b> — additively raising
 * {@code Tenant.maxSubAgents} (+ {@code purchasedSubAgentSeats}, so a later plan-change keeps them) and
 * flipping the {@code PENDING_LICENSE} sub-agent to {@code ACTIVE}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubAgentLicenseServiceImpl implements SubAgentLicenseService {

    private final SubAgentLicenseRequestRepository requestRepository;
    private final SubAgentProfileRepository profileRepository;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final SubAgentSeatFeeService seatFeeService;
    private final BillingService billingService;
    private final BillingRecordRepository billingRecordRepository;
    private final PlatformAuditRecorder platformAuditRecorder;
    private final ApplicationEventPublisher eventPublisher;
    private final CloudinaryService cloudinaryService;

    // ── Open (create / resubmit) ─────────────────────────────────────────────────

    @Override
    @Transactional
    public SubAgentLicenseRequestResponse openForPending(Long tenantId, String requestedByEmail, UUID subAgentPublicId,
                                                         PaymentMode paymentMode, OfflinePaymentMode offlineMode,
                                                         String offlineReference, String offlineNotes) {
        // Pessimistic-write lock the tenant row so two concurrent opens for the same pending sub-agent
        // can't both pass the duplicate check below and issue two payable invoices for one seat.
        Tenant tenant = tenantRepository.findByIdForUpdate(tenantId)
                .orElseThrow(() -> new BusinessException("Your organization could not be found.", HttpStatus.FORBIDDEN));
        if (!tenant.isOperational()) {
            throw new BusinessException("Your account is not active. Contact support.", HttpStatus.FORBIDDEN);
        }

        SubAgentProfile profile = profileRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(subAgentPublicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Sub-agent not found: " + subAgentPublicId));
        if (profile.getStatus() != SubAgentStatus.PENDING_LICENSE) {
            throw new BusinessException("This sub-agent is already licensed.", HttpStatus.BAD_REQUEST);
        }
        if (!requestRepository
                .findBySubAgentProfileIdAndStatusAndDeletedAtIsNull(profile.getId(), SubAgentLicenseRequestStatus.PENDING)
                .isEmpty()) {
            throw new BusinessException(
                    "A seat-license request is already pending for this sub-agent.", HttpStatus.CONFLICT);
        }

        if (paymentMode == null) {
            paymentMode = PaymentMode.ONLINE;
        }
        OfflinePaymentMode resolvedOfflineMode = null;
        String resolvedOfflineRef = null;
        String resolvedOfflineNotes = null;
        if (paymentMode == PaymentMode.OFFLINE) {
            resolvedOfflineMode = offlineMode;
            resolvedOfflineRef = offlineReference != null ? offlineReference.trim() : null;
            resolvedOfflineNotes = offlineNotes;
            if (resolvedOfflineMode == null) {
                throw new BusinessException("Select how you paid (bank transfer / cheque / cash).",
                        HttpStatus.BAD_REQUEST);
            }
            if (!StringUtils.hasText(resolvedOfflineRef)) {
                throw new BusinessException("A payment reference is required for offline payment.",
                        HttpStatus.BAD_REQUEST);
            }
        }

        // Price the one-time unlock (v1: one seat per sub-agent).
        SubAgentSeatFeeService.LicenseFeeQuote quote = seatFeeService.licenseFee(tenant, 1);
        User user = userRepository.findByIdAndTenantIdAndDeletedAtIsNull(profile.getUserId(), tenantId).orElse(null);
        String subAgentName = user != null ? user.getName() : null;
        String subAgentEmail = user != null ? user.getEmail() : null;

        // Issue the payable invoice up-front (untagged, no due date).
        String invoiceNotes = "Sub-agent seat license — " + quote.quantity() + " seat"
                + (quote.quantity() == 1 ? "" : "s")
                + (subAgentName != null ? " for " + subAgentName : "") + " (pending SuperAdmin approval).";
        BillingRecordResponse invoice = billingService.issueSeatLicenseInvoice(
                tenantId, quote.quantity(), quote.total(), invoiceNotes);
        Long invoiceId = billingRecordRepository.findByPublicIdAndDeletedAtIsNull(invoice.getPublicId())
                .map(BillingRecord::getId).orElse(null);

        SubAgentLicenseRequest req = SubAgentLicenseRequest.builder()
                .tenantId(tenant.getId())
                .tenantCode(tenant.getOrganizationCode())
                .tenantName(tenant.getOrganizationName())
                .subAgentProfileId(profile.getId())
                .subAgentProfilePublicId(profile.getPublicId())
                .subAgentName(subAgentName)
                .subAgentEmail(subAgentEmail)
                .quantity(quote.quantity())
                .unitPrice(quote.unitRate())
                .amount(quote.total())
                .currency(invoice.getCurrency())
                .status(SubAgentLicenseRequestStatus.PENDING)
                .paymentMode(paymentMode)
                .offlineMode(resolvedOfflineMode)
                .offlineReference(resolvedOfflineRef)
                .offlineNotes(resolvedOfflineNotes)
                .billingRecordId(invoiceId)
                .billingRecordPublicId(invoice.getPublicId())
                .requestedByEmail(requestedByEmail)
                .build();
        SubAgentLicenseRequest saved = requestRepository.save(req);

        platformAuditRecorder.safeRecord(PlatformAuditAction.SUBAGENT_LICENSE_CREATE, true,
                tenant.getId(), tenant.getOrganizationCode(), "SUBAGENT_LICENSE", saved.getPublicId(),
                "Seat-license purchase (" + quote.quantity() + " seat, " + paymentMode + ") for "
                        + subAgentName + " by " + requestedByEmail);

        log.info("Sub-agent seat-license request opened | tenantId={} subAgent={} amount={} {}",
                tenantId, subAgentPublicId, invoice.getCurrency(), quote.total());
        return toResponse(saved);
    }

    // ── Tenant-facing ───────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<SubAgentLicenseRequestResponse> listForTenant(Long tenantId) {
        return requestRepository.findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(tenantId)
                .stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public SubAgentLicenseRequestResponse uploadProof(Long tenantId, UUID publicId, MultipartFile file) {
        SubAgentLicenseRequest req = requireOwned(tenantId, publicId);
        if (req.getStatus() != SubAgentLicenseRequestStatus.PENDING) {
            throw new BusinessException("Proof can only be added while the request is pending.", HttpStatus.CONFLICT);
        }
        if (req.getPaymentMode() != PaymentMode.OFFLINE) {
            throw new BusinessException("Proof upload applies to offline payments only.", HttpStatus.BAD_REQUEST);
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException("No file provided.", HttpStatus.BAD_REQUEST);
        }
        req.setOfflineProofUrl(cloudinaryService.uploadImage(file, "subagent-license-proofs"));
        return toResponse(requestRepository.save(req));
    }

    @Override
    @Transactional
    public SubAgentLicenseRequestResponse cancel(Long tenantId, UUID publicId) {
        SubAgentLicenseRequest req = requireOwned(tenantId, publicId);
        if (req.getStatus() != SubAgentLicenseRequestStatus.PENDING) {
            throw new BusinessException("Only a pending request can be cancelled.", HttpStatus.CONFLICT);
        }
        BillingRecord invoice = linkedInvoice(req);
        if (invoice != null && invoice.getStatus() == BillingStatus.PAID) {
            throw new BusinessException(
                    "Payment has already been received for this request; it can no longer be cancelled. Contact support.",
                    HttpStatus.CONFLICT);
        }
        if (invoice != null && invoice.getStatus() == BillingStatus.UNPAID) {
            billingService.voidInvoice(invoice.getPublicId());
        }

        // The tenant withdrew the purchase — remove the still-pending sub-agent (soft delete + kill login).
        removePendingSubAgent(tenantId, req.getSubAgentProfileId());

        req.setStatus(SubAgentLicenseRequestStatus.CANCELLED);
        SubAgentLicenseRequest saved = requestRepository.save(req);

        platformAuditRecorder.safeRecord(PlatformAuditAction.SUBAGENT_LICENSE_CANCEL, true,
                req.getTenantId(), req.getTenantCode(), "SUBAGENT_LICENSE", saved.getPublicId(),
                "Cancelled seat-license purchase for " + req.getSubAgentName());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void onSubAgentDeleted(Long tenantId, Long subAgentProfileId) {
        if (subAgentProfileId == null) {
            return;
        }
        List<SubAgentLicenseRequest> live = requestRepository
                .findBySubAgentProfileIdAndStatusAndDeletedAtIsNull(subAgentProfileId, SubAgentLicenseRequestStatus.PENDING);
        for (SubAgentLicenseRequest req : live) {
            if (!Objects.equals(req.getTenantId(), tenantId)) {
                continue;
            }
            BillingRecord invoice = linkedInvoice(req);
            if (invoice != null && invoice.getStatus() == BillingStatus.UNPAID) {
                billingService.voidInvoice(invoice.getPublicId());
            }
            req.setStatus(SubAgentLicenseRequestStatus.CANCELLED);
            req.setDecisionNote("Sub-agent removed before licensing.");
            requestRepository.save(req);
            platformAuditRecorder.safeRecord(PlatformAuditAction.SUBAGENT_LICENSE_CANCEL, true,
                    req.getTenantId(), req.getTenantCode(), "SUBAGENT_LICENSE", req.getPublicId(),
                    "Auto-cancelled seat-license (sub-agent removed): " + req.getSubAgentName());
        }
    }

    // ── SuperAdmin-facing ───────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<SubAgentLicenseRequestResponse> listForAdmin(SubAgentLicenseRequestStatus statusFilter) {
        List<SubAgentLicenseRequest> rows = statusFilter != null
                ? requestRepository.findByStatusAndDeletedAtIsNullOrderByCreatedAtDesc(statusFilter)
                : requestRepository.findByDeletedAtIsNullOrderByCreatedAtDesc();
        return rows.stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long pendingCount() {
        return requestRepository.countByStatusAndDeletedAtIsNull(SubAgentLicenseRequestStatus.PENDING);
    }

    @Override
    @Transactional
    public SubAgentLicenseRequestResponse approve(UUID publicId) {
        SubAgentLicenseRequest req = requireRequest(publicId);
        if (req.getStatus() != SubAgentLicenseRequestStatus.PENDING) {
            throw new BusinessException("Only a pending request can be approved.", HttpStatus.CONFLICT);
        }
        // Lock the tenant row for the read-modify-write on the seat counters, so two concurrent approvals
        // for the same tenant can't lost-update maxSubAgents / purchasedSubAgentSeats (each grant must add).
        Tenant tenant = tenantRepository.findByIdForUpdate(req.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found for request " + publicId));
        BillingRecord invoice = linkedInvoice(req);

        if (req.getPaymentMode() == PaymentMode.ONLINE) {
            if (invoice == null || invoice.getStatus() != BillingStatus.PAID) {
                throw new BusinessException(
                        "Payment is not yet confirmed. The tenant must complete the online payment before approval.",
                        HttpStatus.CONFLICT);
            }
        } else {
            // Offline: approving IS the verification — settle the invoice now (untagged, so no side effect).
            if (invoice != null && invoice.getStatus() == BillingStatus.UNPAID) {
                billingService.markPaid(invoice.getPublicId());
            }
        }

        int qty = Math.max(1, req.getQuantity());

        // Activate the pending sub-agent this purchase is for FIRST — the seat grant is conditioned on it
        // so a partner deleted mid-approval never leaves an orphan paid seat / purchasedSubAgentSeats
        // over-count. If it can't be activated (removed), the seats are NOT granted (payment refunded
        // out-of-band, matching the no-automated-refund policy).
        boolean activated = activatePending(tenant.getId(), req.getSubAgentProfileId());
        if (activated) {
            // THE grant gate: raise the seat cap additively and record the purchase so it survives a
            // future plan-change (TenantPlanApplier re-derives maxSubAgents = plan default + purchased).
            int curMax = tenant.getMaxSubAgents() != null ? Math.max(0, tenant.getMaxSubAgents()) : 0;
            int curPurchased = tenant.getPurchasedSubAgentSeats() != null ? Math.max(0, tenant.getPurchasedSubAgentSeats()) : 0;
            tenant.setMaxSubAgents(curMax + qty);
            tenant.setPurchasedSubAgentSeats(curPurchased + qty);
            tenantRepository.save(tenant);
        } else {
            log.warn("Seat-license approve: pending sub-agent (profileId={}) not activatable (removed?) — "
                    + "seats NOT granted for request {}", req.getSubAgentProfileId(), publicId);
        }

        req.setStatus(SubAgentLicenseRequestStatus.APPROVED);
        stampReviewer(req);
        SubAgentLicenseRequest saved = requestRepository.save(req);

        platformAuditRecorder.safeRecord(PlatformAuditAction.SUBAGENT_LICENSE_APPROVE, true,
                tenant.getId(), tenant.getOrganizationCode(), "SUBAGENT_LICENSE", saved.getPublicId(),
                activated
                        ? "Approved " + qty + " sub-agent seat(s) for " + req.getSubAgentName()
                                + " (" + req.getPaymentMode() + "); seat cap now " + tenant.getMaxSubAgents()
                        : "Approved seat-license for " + req.getSubAgentName() + " but partner was removed — "
                                + "seats not granted (" + req.getPaymentMode() + ")");

        notifyTenantAdmins(tenant, "SUBAGENT_LICENSE_APPROVED", saved.getPublicId(), "Sub-agent activated",
                "Your Travel Partner " + safe(req.getSubAgentName())
                        + " has been licensed and activated. They can now sign in.");

        return toResponse(saved);
    }

    @Override
    @Transactional
    public SubAgentLicenseRequestResponse reject(UUID publicId, String reason) {
        SubAgentLicenseRequest req = requireRequest(publicId);
        if (req.getStatus() != SubAgentLicenseRequestStatus.PENDING) {
            throw new BusinessException("Only a pending request can be rejected.", HttpStatus.CONFLICT);
        }
        Tenant tenant = tenantRepository.findById(req.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found for request " + publicId));
        BillingRecord invoice = linkedInvoice(req);
        boolean alreadyPaid = invoice != null && invoice.getStatus() == BillingStatus.PAID;

        // Void an unpaid invoice; a paid (online) one is left settled and refunded out-of-band.
        if (invoice != null && invoice.getStatus() == BillingStatus.UNPAID) {
            billingService.voidInvoice(invoice.getPublicId());
        }

        // The sub-agent stays PENDING_LICENSE (no portal access); the tenant may resubmit payment.
        req.setStatus(SubAgentLicenseRequestStatus.REJECTED);
        req.setDecisionNote(reason);
        stampReviewer(req);
        SubAgentLicenseRequest saved = requestRepository.save(req);

        platformAuditRecorder.safeRecord(PlatformAuditAction.SUBAGENT_LICENSE_REJECT, true,
                tenant.getId(), tenant.getOrganizationCode(), "SUBAGENT_LICENSE", saved.getPublicId(),
                "Rejected seat-license for " + req.getSubAgentName() + ": " + reason
                        + (alreadyPaid ? " (payment received — refund pending)" : ""));

        notifyTenantAdmins(tenant, "SUBAGENT_LICENSE_REJECTED", saved.getPublicId(), "Seat purchase declined",
                "Your seat purchase for " + safe(req.getSubAgentName()) + " was not approved. Reason: " + reason
                        + (alreadyPaid ? " Your payment will be refunded." : " You can resubmit payment."));

        return toResponse(saved);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Flip the linked PENDING_LICENSE profile → ACTIVE and enable its login. Returns true if it did. */
    private boolean activatePending(Long tenantId, Long profileId) {
        if (profileId == null) {
            return false;
        }
        return profileRepository.findByIdAndTenantIdAndDeletedAtIsNull(profileId, tenantId)
                .filter(p -> p.getStatus() == SubAgentStatus.PENDING_LICENSE)
                .map(p -> {
                    p.setStatus(SubAgentStatus.ACTIVE);
                    profileRepository.save(p);
                    userRepository.findByIdAndTenantIdAndDeletedAtIsNull(p.getUserId(), tenantId).ifPresent(u -> {
                        u.setIsActive(true);
                        userRepository.save(u);
                    });
                    return true;
                })
                .orElse(false);
    }

    private void removePendingSubAgent(Long tenantId, Long profileId) {
        if (profileId == null) {
            return;
        }
        String actor = currentActorEmail();
        profileRepository.findByIdAndTenantIdAndDeletedAtIsNull(profileId, tenantId)
                .filter(p -> p.getStatus() == SubAgentStatus.PENDING_LICENSE)
                .ifPresent(p -> {
                    p.softDelete(actor);
                    profileRepository.save(p);
                    userRepository.findByIdAndTenantIdAndDeletedAtIsNull(p.getUserId(), tenantId).ifPresent(u -> {
                        u.setIsActive(false);
                        u.softDelete(actor);
                        userRepository.save(u);
                    });
                });
    }

    private SubAgentLicenseRequest requireRequest(UUID publicId) {
        return requestRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Seat-license request not found: " + publicId));
    }

    /** Object-level ownership: a foreign request 404s, never leaks. */
    private SubAgentLicenseRequest requireOwned(Long tenantId, UUID publicId) {
        SubAgentLicenseRequest req = requireRequest(publicId);
        if (!Objects.equals(req.getTenantId(), tenantId)) {
            throw new ResourceNotFoundException("Seat-license request not found: " + publicId);
        }
        return req;
    }

    private BillingRecord linkedInvoice(SubAgentLicenseRequest req) {
        return req.getBillingRecordPublicId() != null
                ? billingRecordRepository.findByPublicIdAndDeletedAtIsNull(req.getBillingRecordPublicId()).orElse(null)
                : null;
    }

    private void stampReviewer(SubAgentLicenseRequest req) {
        PlatformActor actor = PlatformContext.getActor();
        req.setReviewedByEmail(actor != null ? actor.email() : null);
        req.setReviewedAt(LocalDateTime.now());
    }

    private String currentActorEmail() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null ? a.getName() : "system";
    }

    private static String safe(String s) {
        return StringUtils.hasText(s) ? s : "sub-agent";
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
                .referenceType("SUBAGENT_LICENSE")
                .referencePublicId(referencePublicId)
                .channels(Set.of(DeliveryChannel.IN_APP))
                .build());
    }

    private SubAgentLicenseRequestResponse toResponse(SubAgentLicenseRequest req) {
        BillingRecord invoice = linkedInvoice(req);
        BillingStatus invoiceStatus = invoice != null ? invoice.getStatus() : null;
        BigDecimal unitPrice = req.getUnitPrice();

        return SubAgentLicenseRequestResponse.builder()
                .publicId(req.getPublicId())
                .tenantCode(req.getTenantCode())
                .tenantName(req.getTenantName())
                .subAgentPublicId(req.getSubAgentProfilePublicId())
                .subAgentName(req.getSubAgentName())
                .subAgentEmail(req.getSubAgentEmail())
                .quantity(req.getQuantity())
                .unitPrice(unitPrice)
                .amount(req.getAmount())
                .currency(req.getCurrency())
                .status(req.getStatus())
                .paymentMode(req.getPaymentMode())
                .offlineMode(req.getOfflineMode())
                .offlineReference(req.getOfflineReference())
                .offlineNotes(req.getOfflineNotes())
                .offlineProofUrl(req.getOfflineProofUrl())
                .invoicePublicId(req.getBillingRecordPublicId())
                .invoiceNumber(invoice != null ? invoice.getInvoiceNumber() : null)
                .invoiceStatus(invoiceStatus)
                .paymentConfirmed(invoiceStatus == BillingStatus.PAID)
                .requestedByEmail(req.getRequestedByEmail())
                .reviewedByEmail(req.getReviewedByEmail())
                .reviewedAt(req.getReviewedAt())
                .decisionNote(req.getDecisionNote())
                .createdAt(req.getCreatedAt())
                .build();
    }
}