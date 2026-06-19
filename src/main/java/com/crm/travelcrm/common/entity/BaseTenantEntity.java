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
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public abstract class BaseTenantEntity extends BaseEntity {

    // No DB-level FK to tenants.id — tenant isolation is enforced at the application layer
    // (TenantEntityListener auto-stamp + Hibernate @Filter("tenantFilter")), not by a constraint.
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;
}