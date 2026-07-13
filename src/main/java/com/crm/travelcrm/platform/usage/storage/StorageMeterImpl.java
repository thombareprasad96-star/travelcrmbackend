package com.crm.travelcrm.platform.usage.storage;

import com.crm.travelcrm.common.cloudinary.StorageMeter;
import com.crm.travelcrm.common.context.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists per-tenant Cloudinary asset sizes so the SuperAdmin usage dashboard can total storage.
 * <b>Best-effort:</b> any failure is logged and swallowed so metering never breaks the upload/delete
 * it observes. Each write runs in its own transaction ({@link Propagation#REQUIRES_NEW}) so it is
 * independent of the caller's transaction (an image upload endpoint may not even be transactional).
 * The tenant is read from {@link TenantContext} (uploads happen on the tenant request thread); an
 * upload with no tenant in context (platform-side) is simply not metered.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageMeterImpl implements StorageMeter {

    private final TenantStorageAssetRepository repository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUpload(String cloudinaryPublicId, String url, long sizeBytes,
                             String resourceType, String folder) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return;   // no tenant context (platform/unauthenticated) — not metered
        }
        try {
            repository.save(TenantStorageAsset.builder()
                    .tenantId(tenantId)
                    .cloudinaryPublicId(cloudinaryPublicId)
                    .url(url)
                    .sizeBytes(Math.max(sizeBytes, 0))
                    .resourceType(resourceType)
                    .folder(folder)
                    .build());
        } catch (Exception e) {
            log.warn("Storage meter (upload) failed for publicId {}: {}", cloudinaryPublicId, e.getMessage());
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDelete(String cloudinaryPublicId) {
        if (cloudinaryPublicId == null || cloudinaryPublicId.isBlank()) {
            return;
        }
        try {
            repository.deleteByCloudinaryPublicId(cloudinaryPublicId);
        } catch (Exception e) {
            log.warn("Storage meter (delete) failed for publicId {}: {}", cloudinaryPublicId, e.getMessage());
        }
    }
}