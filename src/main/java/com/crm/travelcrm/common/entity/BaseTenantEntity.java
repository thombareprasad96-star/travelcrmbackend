package com.crm.travelcrm.common.entity;

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

// Hibernate filter — when enabled, ALL queries on this entity
// automatically add WHERE tenant_id = :tenantId
// This is your safety net against cross-tenant data leaks
@FilterDef(
        name       = "tenantFilter",
        parameters = @ParamDef(name = "tenantId", type = Long.class)
)
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public abstract class BaseTenantEntity extends BaseEntity {

    // Long to match BaseEntity's ID type — consistent join strategy
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;
}