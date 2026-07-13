package com.crm.travelcrm.platform.subscription.upgrade.service;

import com.crm.travelcrm.platform.subscription.upgrade.dto.CreateUpgradeRequestRequest;
import com.crm.travelcrm.platform.subscription.upgrade.dto.UpgradeRequestResponse;
import com.crm.travelcrm.platform.subscription.upgrade.enums.UpgradeRequestStatus;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Tenant-initiated plan-upgrade requests with a SuperAdmin approval gate. Tenant-facing methods take
 * the caller's own {@code tenantId} (from the principal) and enforce object-level ownership; the
 * SuperAdmin-facing methods are cross-tenant and reached only from the {@code /api/super-admin} chain.
 */
public interface UpgradeRequestService {

    // ── Tenant-facing (scoped to the caller's own tenant) ───────────────────────

    UpgradeRequestResponse create(Long tenantId, String requestedByEmail, CreateUpgradeRequestRequest request);

    UpgradeRequestResponse uploadProof(Long tenantId, UUID publicId, MultipartFile file);

    List<UpgradeRequestResponse> listForTenant(Long tenantId);

    UpgradeRequestResponse cancel(Long tenantId, UUID publicId);

    // ── SuperAdmin-facing (cross-tenant) ────────────────────────────────────────

    /** @param statusFilter optional; {@code null} = all. */
    List<UpgradeRequestResponse> listForAdmin(UpgradeRequestStatus statusFilter);

    long pendingCount();

    UpgradeRequestResponse approve(UUID publicId);

    UpgradeRequestResponse reject(UUID publicId, String reason);
}
