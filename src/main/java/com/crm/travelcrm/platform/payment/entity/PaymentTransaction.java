package com.crm.travelcrm.platform.payment.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import com.crm.travelcrm.platform.payment.enums.PaymentTransactionStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * One SaaS payment attempt (platform ← tenant) against a platform invoice. <b>Platform-level</b>
 * (extends {@link BaseEntity}, not {@code BaseTenantEntity}) — it belongs to the platform's billing,
 * not any tenant's data, so the tenant {@code @Filter} must never touch it. The tenant is referenced
 * by the logical FK {@code tenantId}; code/name are snapshotted for a stable audit trail.
 *
 * <p>{@code gatewayOrderId} is unique — it is the join key the webhook uses to find this row when the
 * gateway reports capture/failure.
 */
@Entity
@Table(name = "saas_payment_transactions",
        uniqueConstraints = @UniqueConstraint(name = "uq_saas_pay_order", columnNames = "gateway_order_id"),
        indexes = {
                @Index(name = "idx_saas_pay_tenant", columnList = "tenant_id"),
                @Index(name = "idx_saas_pay_payment", columnList = "gateway_payment_id"),
                @Index(name = "idx_saas_pay_invoice", columnList = "billing_record_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PaymentTransaction extends BaseEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "tenant_code", length = 50)
    private String tenantCode;

    @Column(name = "tenant_name", length = 150)
    private String tenantName;

    /** Logical FK to {@code billing_records.id} — the invoice this attempt settles ({@code null} if unlinked). */
    @Column(name = "billing_record_id")
    private Long billingRecordId;

    @Column(name = "invoice_number", length = 30)
    private String invoiceNumber;

    @Column(name = "provider", length = 20)
    @Builder.Default
    private String provider = "RAZORPAY";

    @Column(name = "gateway_order_id", nullable = false, length = 64)
    private String gatewayOrderId;

    /** Set when the gateway reports a successful capture. */
    @Column(name = "gateway_payment_id", length = 64)
    private String gatewayPaymentId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", length = 8)
    @Builder.Default
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private PaymentTransactionStatus status = PaymentTransactionStatus.CREATED;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "notes", length = 500)
    private String notes;
}
