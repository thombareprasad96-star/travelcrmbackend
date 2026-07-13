package com.crm.travelcrm.platform.usage.storage;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * One row per Cloudinary asset a tenant has uploaded (images, generated PDFs). Records the stored
 * byte size so per-tenant storage usage is computable — Cloudinary keeps no per-tenant total and the
 * entities that reference an asset only persist its URL string, not its size. Written by
 * {@code StorageMeterImpl} on upload and removed on delete. Tenant-scoped + soft-deletable via
 * {@link BaseTenantEntity}.
 */
@Entity
@Table(name = "tenant_storage_assets", indexes = {
        @Index(name = "idx_storage_asset_tenant",    columnList = "tenant_id"),
        @Index(name = "idx_storage_asset_public_id", columnList = "cloudinary_public_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class TenantStorageAsset extends BaseTenantEntity {

    /** Cloudinary public id (incl. folder), the key used to remove the row when the asset is deleted. */
    @Column(name = "cloudinary_public_id", length = 300)
    private String cloudinaryPublicId;

    @Column(name = "url", length = 500)
    private String url;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Cloudinary {@code resource_type}: "image" or "raw". */
    @Column(name = "resource_type", length = 20)
    private String resourceType;

    @Column(name = "folder", length = 150)
    private String folder;
}