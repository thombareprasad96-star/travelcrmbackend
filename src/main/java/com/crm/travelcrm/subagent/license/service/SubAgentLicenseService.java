package com.crm.travelcrm.subagent.license.service;

import com.crm.travelcrm.platform.subscription.upgrade.enums.OfflinePaymentMode;
import com.crm.travelcrm.platform.subscription.upgrade.enums.PaymentMode;
import com.crm.travelcrm.subagent.license.dto.SubAgentLicenseRequestResponse;
import com.crm.travelcrm.subagent.license.enums.SubAgentLicenseRequestStatus;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * The sub-agent (Travel Partner) seat-license approval flow — the seat-add-on sibling of
 * {@code UpgradeRequestService}. A request holds the tenant's purchase in {@code PENDING}, the payment
 * settles the linked invoice up-front (online via Razorpay webhook, or offline at approval), and
 * <b>only SuperAdmin approval grants the seats</b> (raises the cap + activates the pending sub-agent).
 */
public interface SubAgentLicenseService {

    /**
     * Open a seat-license purchase for an existing {@code PENDING_LICENSE} sub-agent (looked up by its
     * profile publicId, tenant-scoped). Used both when a sub-agent is first provisioned over cap and to
     * resubmit payment after a rejection. Issues the payable invoice and returns the request (with the
     * invoice publicId the tenant pays online). Fails if a live PENDING request already exists for it.
     */
    SubAgentLicenseRequestResponse openForPending(Long tenantId, String requestedByEmail, UUID subAgentPublicId,
                                                  PaymentMode paymentMode, OfflinePaymentMode offlineMode,
                                                  String offlineReference, String offlineNotes);

    // ── Tenant-facing ───────────────────────────────────────────────────────────
    List<SubAgentLicenseRequestResponse> listForTenant(Long tenantId);

    SubAgentLicenseRequestResponse uploadProof(Long tenantId, UUID publicId, MultipartFile file);

    SubAgentLicenseRequestResponse cancel(Long tenantId, UUID publicId);

    /** Void + cancel any live request when its pending sub-agent is deleted. Best-effort; no-op if none. */
    void onSubAgentDeleted(Long tenantId, Long subAgentProfileId);

    // ── SuperAdmin-facing ─────────────────────────────────────────────────────────
    List<SubAgentLicenseRequestResponse> listForAdmin(SubAgentLicenseRequestStatus statusFilter);

    long pendingCount();

    SubAgentLicenseRequestResponse approve(UUID publicId);

    SubAgentLicenseRequestResponse reject(UUID publicId, String reason);
}