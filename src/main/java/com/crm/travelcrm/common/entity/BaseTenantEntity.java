package com.crm.travelcrm.common.entity;

import com.crm.travelcrm.common.listener.TenantEntityListener;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@MappedSuperclass
@SuperBuilder
@EntityListeners(TenantEntityListener.class)
@FilterDef(
        name       = "tenantFilter",
        parameters = @ParamDef(name = "tenantId", type = Long.class)
)
// Soft-delete filter — DECLARED here (globally) but only APPLIED (@Filter) on the entities that
// opt in (master data: Hotel, Vehicle, Airline, …). Those modules have many finders that don't
// each carry a `deletedAt IS NULL` predicate, so this filter hides trashed rows from every read
// in one place. Core CRM entities (Lead, Customer, …) instead use explicit `...DeletedAtIsNull`
// finders and deliberately do NOT apply this filter. The Trash module disables it to see trashed
// rows. Enabled per-session by TenantFilterAspect.
@FilterDef(name = "softDeleteFilter")
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public abstract class BaseTenantEntity extends BaseEntity {

    // No DB-level FK to tenants.id — tenant isolation is enforced at the application layer
    // (TenantEntityListener auto-stamp + Hibernate @Filter("tenantFilter")), not by a constraint.
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;
}