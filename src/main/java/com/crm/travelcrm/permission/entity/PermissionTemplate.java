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

// A reusable permission template, scoped to one tenant. `value` is a stable slug
// key (unique per tenant); the permission map is stored as JSON.
@Entity
@Table(
    name = "permission_templates",
    uniqueConstraints = @UniqueConstraint(
            name = "uq_permission_templates_value", columnNames = {"tenant_id", "template_value"}),
    indexes = @Index(name = "idx_permission_templates_tenant", columnList = "tenant_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PermissionTemplate extends BaseTenantEntity {

    @Column(name = "template_value", nullable = false, length = 100)
    private String value;

    @Column(name = "label", nullable = false, length = 150)
    private String label;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    // JSON: { "<pageId>": { "access": true, "scope": "own" }, ... }
    @Column(name = "permissions_json", columnDefinition = "TEXT")
    private String permissionsJson;
}