package com.crm.travelcrm.platform.usage.storage;

import com.crm.travelcrm.common.cloudinary.StorageQuota;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.portal.document.repository.TravelerDocumentRepository;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hard storage-quota gate. Sums the tenant's Cloudinary asset bytes ({@code TenantStorageAsset}) and
 * traveler-document bytes ({@code TravelerDocument}) — exactly the two sources the SuperAdmin usage
 * dashboard totals — and blocks an upload that would exceed {@code Tenant.maxStorageMb}. Mirrors the
 * user-seat and booking-per-month hard gates; storage was previously only metered + alerted.
 */
@Service
@RequiredArgsConstructor
public class StorageQuotaGuard implements StorageQuota {

    private static final long BYTES_PER_MB = 1024L * 1024L;

    private final TenantRepository tenantRepository;
    private final TenantStorageAssetRepository storageAssetRepository;
    private final TravelerDocumentRepository travelerDocumentRepository;

    @Override
    public void enforceWithinQuota(long incomingBytes) {
        enforceWithinQuota(TenantContext.getTenantId(), incomingBytes);
    }

    @Override
    @Transactional(readOnly = true)
    public void enforceWithinQuota(Long tenantId, long incomingBytes) {
        if (tenantId == null) {
            return;   // no tenant (platform/unauthenticated) — not enforced
        }
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) {
            return;
        }
        Integer maxMb = tenant.getMaxStorageMb();
        if (maxMb == null || maxMb <= 0) {
            return;   // unlimited
        }
        long limit = maxMb * BYTES_PER_MB;
        long used = storageAssetRepository.sumBytesByTenant(tenantId)
                + travelerDocumentRepository.sumBytesByTenant(tenantId);
        if (used + Math.max(0, incomingBytes) > limit) {
            throw new BusinessException(
                    "Storage limit reached (" + maxMb + " MB). Remove files or upgrade the plan "
                            + "to upload more.",
                    HttpStatus.FORBIDDEN);
        }
    }
}