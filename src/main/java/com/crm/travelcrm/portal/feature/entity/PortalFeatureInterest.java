package com.crm.travelcrm.portal.feature.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.portal.feature.PortalFeatureKey;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * A traveler's "Notify me" registration for a not-yet-built ("Coming Soon") feature. 100% additive:
 * a new {@code portal_feature_interest} table, tenant-scoped via {@link BaseTenantEntity}, with a
 * logical FK to {@code customers.id} (the object-level ownership key — no DB constraint, same pattern
 * as the rest of the portal). The unique constraint makes "Notify me" idempotent per customer+feature.
 */
@Entity
@Table(
        name = "portal_feature_interest",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_portal_feature_interest_customer_feature",
                columnNames = {"tenant_id", "customer_id", "feature_key"}),
        indexes = {
                @Index(name = "idx_pfi_tenant_customer", columnList = "tenant_id, customer_id"),
                @Index(name = "idx_pfi_tenant_feature",  columnList = "tenant_id, feature_key")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PortalFeatureInterest extends BaseTenantEntity {

    /** Logical FK to {@code customers.id} — object-level ownership key (no DB-level FK). */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** The customer's UUID — safe to echo; never the internal Long id leaves the API. */
    @Column(name = "customer_public_id", nullable = false)
    private UUID customerPublicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "feature_key", nullable = false, length = 30)
    private PortalFeatureKey featureKey;
}