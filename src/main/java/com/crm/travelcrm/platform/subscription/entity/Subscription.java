package com.crm.travelcrm.platform.subscription.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import com.crm.travelcrm.platform.subscription.enums.SubscriptionStatus;
import com.crm.travelcrm.tenent.enums.TenantPlan;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;

/**
 * A tenant's recurring subscription at the payment gateway. <b>Scaffold</b> for the
 * "Subscriptions (auto-debit)" path chosen alongside Orders: the entity, repository and webhook
 * routing branches exist so recurring can be switched on later without a schema rework. Nothing
 * creates rows here yet — the live billing path is Orders/pay-per-invoice ({@code PaymentTransaction}).
 *
 * <p>Platform-level (extends {@link BaseEntity}); tenant referenced by the logical FK {@code tenantId}.
 */
@Entity
@Table(name = "subscriptions",
        indexes = {
                @Index(name = "idx_subscription_tenant", columnList = "tenant_id"),
                @Index(name = "idx_subscription_gateway", columnList = "gateway_subscription_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Subscription extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_code", length = 20)
    private TenantPlan planCode;

    @Column(name = "provider", length = 20)
    @Builder.Default
    private String provider = "RAZORPAY";

    /** Gateway subscription id (Razorpay {@code sub_…}). */
    @Column(name = "gateway_subscription_id", length = 64)
    private String gatewaySubscriptionId;

    /** Gateway plan id (Razorpay {@code plan_…}). */
    @Column(name = "gateway_plan_id", length = 64)
    private String gatewayPlanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private SubscriptionStatus status = SubscriptionStatus.CREATED;

    @Column(name = "current_period_start")
    private LocalDate currentPeriodStart;

    @Column(name = "current_period_end")
    private LocalDate currentPeriodEnd;

    /** Razorpay-hosted authorization link (e-mandate approval). */
    @Column(name = "short_url", length = 500)
    private String shortUrl;

    /** Total billing cycles committed / already charged. */
    @Column(name = "total_count")
    private Integer totalCount;

    @Column(name = "paid_count")
    private Integer paidCount;
}