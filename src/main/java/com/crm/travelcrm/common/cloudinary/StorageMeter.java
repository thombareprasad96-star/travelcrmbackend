package com.crm.travelcrm.common.cloudinary;

/**
 * SPI for recording per-tenant storage usage as assets are uploaded / deleted through
 * {@link CloudinaryService}. Declared here (in {@code common}) as an interface so {@code common}
 * carries no dependency on the platform usage module; the implementation lives in
 * {@code platform.usage.storage}. All calls are <b>best-effort</b> — an implementation must never
 * throw in a way that breaks the upload/delete it is observing.
 */
public interface StorageMeter {

    /** Record that the current tenant stored an asset of {@code sizeBytes} at {@code cloudinaryPublicId}. */
    void recordUpload(String cloudinaryPublicId, String url, long sizeBytes, String resourceType, String folder);

    /** Record that an asset was removed from storage (decrements the tenant's total). */
    void recordDelete(String cloudinaryPublicId);
}