package com.crm.travelcrm.company.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;

// A tenant's tax rate (GST/TCS/…). Adding a new rate of a type closes the previous
// active one (effective_to = new effective_from - 1 day).
@Entity
@Table(
    name = "tax_rates",
    indexes = {
        @Index(name = "idx_tax_rates_tenant", columnList = "tenant_id"),
        @Index(name = "idx_tax_rates_type",   columnList = "type")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class TaxRate extends BaseTenantEntity {

    @Column(name = "type", nullable = false, length = 50)
    private String type;

    @Column(name = "rate", nullable = false, precision = 6, scale = 2)
    private BigDecimal rate;

    @Column(name = "calculation", length = 20)
    private String calculation;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;
}