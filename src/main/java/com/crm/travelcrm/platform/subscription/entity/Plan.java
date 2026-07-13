package com.crm.travelcrm.platform.subscription.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import com.crm.travelcrm.tenent.enums.TenantPlan;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/**
 * A subscription plan and its entitlements. <b>Platform-level</b> (extends {@link BaseEntity}, not
 * {@code BaseTenantEntity}) — plans are global catalogue data owned by the SuperAdmin, one row per
 * {@link TenantPlan} code. A tenant references its plan by that {@code code} ({@code Tenant.plan}),
 * so the enum stays the stable identifier while the limits/pricing here are editable at runtime.
 *
 * <p>{@code maxUsers} / {@code maxLeads} are {@code null} = unlimited. {@code modules} lists the
 * module keys the plan unlocks; enforcement of module access + lead limits lands in the Feature
 * Flags phase — this phase only stores and displays them (max-users IS enforced).
 */
@Entity
@Table(name = "plans", uniqueConstraints = @UniqueConstraint(name = "uq_plan_code", columnNames = "code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Plan extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "code", nullable = false, length = 20)
    private TenantPlan code;

    @Column(name = "display_name", nullable = false, length = 60)
    private String displayName;

    @Builder.Default
    @Column(name = "monthly_price", precision = 12, scale = 2)
    private BigDecimal monthlyPrice = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "currency", length = 8)
    private String currency = "INR";

    /** Max users allowed on this plan; {@code null} = unlimited. */
    @Column(name = "max_users")
    private Integer maxUsers;

    /** Max leads allowed on this plan; {@code null} = unlimited. */
    @Column(name = "max_leads")
    private Integer maxLeads;

    /** Max NEW bookings allowed per calendar month on this plan; {@code null} = unlimited. */
    @Column(name = "max_bookings_per_month")
    private Integer maxBookingsPerMonth;

    /** Storage cap in megabytes (Cloudinary assets + traveler documents); {@code null} = unlimited. */
    @Column(name = "max_storage_mb")
    private Integer maxStorageMb;

    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_modules", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "module", length = 40)
    private Set<String> modules = new HashSet<>();

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;
}