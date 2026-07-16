package com.crm.travelcrm.accounting.tax.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * A tenant's HSN/SAC → GST-rate master row. Travel services are SAC 9985 (support services) — a tour
 * operator package is commonly 998552 taxed at 5% <b>without</b> input tax credit, while agent
 * commission (998551) is 18% <b>with</b> ITC. This master lets each tenant configure the codes and
 * rates it actually uses, and marks one row as the default applied to invoice lines that don't name a
 * specific code.
 *
 * <p>Tenant-scoped; soft-delete via {@code deletedAt}. Uniqueness of {@code (tenant_id, code)} across
 * live rows is enforced by a partial unique index (see {@code db/indexes.sql}).
 */
@Entity
@Table(
        name = "hsn_sac_rates",
        indexes = {
                @Index(name = "idx_hsnsac_tenant",  columnList = "tenant_id"),
                @Index(name = "idx_hsnsac_code",    columnList = "tenant_id,code"),
                @Index(name = "idx_hsnsac_deleted", columnList = "tenant_id,deleted_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class HsnSacRate extends BaseTenantEntity {

    /** HSN (goods) or SAC (services) code, e.g. "998552". */
    @Column(name = "code", nullable = false, length = 12)
    private String code;

    @Column(name = "description", length = 200)
    private String description;

    /** Free-text grouping shown in the picker, e.g. "Tour Package", "Commission", "Hotel". */
    @Column(name = "category", length = 60)
    private String category;

    /** Total GST rate percent (e.g. 5.00, 12.00, 18.00). Split into CGST/SGST or IGST at invoice time. */
    @Builder.Default
    @Column(name = "gst_rate_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal gstRatePct = BigDecimal.ZERO;

    /** GST compensation cess percent, if any (usually 0 for travel). */
    @Builder.Default
    @Column(name = "cess_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal cessPct = BigDecimal.ZERO;

    /** Whether input tax credit may be claimed on this supply (false for the 5%-no-ITC tour scheme). */
    @Builder.Default
    @Column(name = "itc_eligible", nullable = false)
    private boolean itcEligible = false;

    /** The fallback rate applied to invoice lines that don't specify a code. At most one per tenant. */
    @Builder.Default
    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;
}