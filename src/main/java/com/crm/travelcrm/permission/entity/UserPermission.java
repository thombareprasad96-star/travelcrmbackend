package com.crm.travelcrm.permission.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

// One row per (tenant, user) holding that user's whole permission map as JSON.
// tenant_id is auto-stamped by TenantEntityListener; userId is the internal id of
// the target user (never exposed — APIs key by the user's publicId).
@Entity
@Table(
    name = "user_permissions",
    uniqueConstraints = @UniqueConstraint(
            name = "uq_user_permissions_user", columnNames = {"tenant_id", "user_id"}),
    indexes = @Index(name = "idx_user_permissions_user", columnList = "user_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class UserPermission extends BaseTenantEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // JSON: { "<pageId>": { "access": true, "scope": "own" }, ... }
    @Column(name = "permissions_json", columnDefinition = "TEXT")
    private String permissionsJson;
}