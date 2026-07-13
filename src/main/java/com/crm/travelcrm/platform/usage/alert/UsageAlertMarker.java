package com.crm.travelcrm.platform.usage.alert;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * Idempotency marker + history for usage alerts. A row exists once a given
 * {@code (tenant, metric, period, level)} alert has fired, so the daily scan never re-spams the same
 * warning. {@code period} is the calendar month ({@code yyyy-MM}) so a persistent over-limit
 * re-notifies at most once per month per level. Tenant-scoped via {@link BaseTenantEntity}.
 */
@Entity
@Table(name = "usage_alert_markers",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_usage_alert", columnNames = {"tenant_id", "metric", "period", "level"}),
        indexes = @Index(name = "idx_usage_alert_tenant", columnList = "tenant_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class UsageAlertMarker extends BaseTenantEntity {

    /** USERS | BOOKINGS | STORAGE. */
    @Column(name = "metric", nullable = false, length = 20)
    private String metric;

    /** Calendar month {@code yyyy-MM} the alert belongs to. */
    @Column(name = "period", nullable = false, length = 7)
    private String period;

    /** WARN | OVER. */
    @Column(name = "level", nullable = false, length = 10)
    private String level;

    @Column(name = "used_value", nullable = false)
    private long usedValue;

    @Column(name = "limit_value")
    private Long limitValue;
}