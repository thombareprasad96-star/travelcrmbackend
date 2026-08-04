package com.crm.travelcrm.common.cloudinary;

/**
 * SPI for hard-enforcing a tenant's storage cap at upload time. Declared in {@code common} (like
 * {@link StorageMeter}) so {@code common} carries no dependency on the platform usage module; the
 * implementation lives in {@code platform.usage.storage}. Unlike {@link StorageMeter} (best-effort,
 * never throws), this is a GATE — it throws {@code BusinessException(403)} when an upload would push
 * the tenant over its plan's {@code maxStorageMb}. No tenant / unlimited plan ⇒ no-op.
 */
public interface StorageQuota {

    /** Enforce for the current tenant (from {@code TenantContext}); no-op when there is none. */
    void enforceWithinQuota(long incomingBytes);

    /** Enforce for an explicit tenant — for realms (e.g. the traveler portal) that pass it directly. */
    void enforceWithinQuota(Long tenantId, long incomingBytes);

    /**
     * Enforce against {@code limit × graceFactor} instead of the plain limit. For uploads that must
     * not be refused at the line — a fleet compliance certificate that has to be on file beats a
     * full disk — while still stopping gross overage. {@code graceFactor} ≤ 1 behaves exactly like
     * the plain gate; the default keeps existing implementations working unchanged.
     */
    default void enforceWithinQuota(Long tenantId, long incomingBytes, double graceFactor) {
        enforceWithinQuota(tenantId, incomingBytes);
    }
}