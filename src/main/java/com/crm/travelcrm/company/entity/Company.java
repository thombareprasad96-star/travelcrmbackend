package com.crm.travelcrm.company.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

// Editable organization profile — exactly one row per tenant. This is a separate
// record from the Tenant registration aggregate (which is not touched here);
// tenant_id is auto-stamped by TenantEntityListener and unique per tenant.
@Entity
@Table(
    name = "companies",
    uniqueConstraints = @UniqueConstraint(name = "uq_companies_tenant", columnNames = "tenant_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Company extends BaseTenantEntity {

    @Column(name = "name", length = 255)
    private String name;

    @Column(name = "prefix", length = 10)
    private String prefix;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "website", length = 500)
    private String website;

    @Column(name = "operating_since")
    private Integer operatingSince;

    @Column(name = "total_reviews")
    private Integer totalReviews;

    @Column(name = "trips_sold")
    private Integer tripsSold;

    @Column(name = "gstin", length = 15)
    private String gstin;

    @Column(name = "tan", length = 10)
    private String tan;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "state", length = 100)
    private String state;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "favicon_url", length = 500)
    private String faviconUrl;
}