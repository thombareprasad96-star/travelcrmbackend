package com.crm.travelcrm.platform.usage.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface TenantStorageAssetRepository extends JpaRepository<TenantStorageAsset, Long> {

    /**
     * Total stored bytes grouped by tenant across ALL tenants (SuperAdmin usage dashboard). Each row
     * is {@code [tenantId (Long), sumBytes (Long)]}. Runs cross-tenant from the SuperAdmin context
     * (TenantContext null ⇒ the Hibernate tenant filter is off).
     */
    @Query("SELECT a.tenantId, COALESCE(SUM(a.sizeBytes), 0) FROM TenantStorageAsset a " +
            "WHERE a.deletedAt IS NULL GROUP BY a.tenantId")
    List<Object[]> sumBytesGroupedByTenant();

    /** Total stored bytes for one tenant. */
    @Query("SELECT COALESCE(SUM(a.sizeBytes), 0) FROM TenantStorageAsset a " +
            "WHERE a.deletedAt IS NULL AND a.tenantId = :tenantId")
    long sumBytesByTenant(@Param("tenantId") Long tenantId);

    /**
     * Best-effort removal when the underlying Cloudinary asset is deleted. Cloudinary public ids are
     * globally unique (folder + generated id), so a delete-by-public-id is unambiguous and safe. This
     * is a bulk delete (bypasses the soft-delete convention deliberately — the asset is really gone).
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM TenantStorageAsset a WHERE a.cloudinaryPublicId = :publicId")
    void deleteByCloudinaryPublicId(@Param("publicId") String publicId);
}