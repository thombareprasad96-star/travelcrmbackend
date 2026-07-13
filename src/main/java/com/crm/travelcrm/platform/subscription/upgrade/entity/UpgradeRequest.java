package com.crm.travelcrm.platform.subscription.upgrade.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import com.crm.travelcrm.platform.subscription.upgrade.enums.OfflinePaymentMode;
import com.crm.travelcrm.platform.subscription.upgrade.enums.PaymentMode;
import com.crm.travelcrm.platform.subscription.upgrade.enums.UpgradeRequestStatus;
import com.crm.travelcrm.tenent.enums.TenantPlan;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A tenant-initiated request to UPGRADE to a higher plan, which a SuperAdmin must review and approve
 * before the plan actually activates.
 *
 * <p><b>Platform-level</b> (extends {@link BaseEntity}, not {@code BaseTenantEntity}) — exactly like
 * {@code BillingRecord}: it is reviewed by a SuperAdmin who has no {@code TenantContext}, so it must
 * never be caught by the tenant {@code @Filter}. The owning tenant is referenced by the logical FK
 * {@code tenantId}; {@code tenantCode}/{@code tenantName}/{@code currentPlan} are snapshotted at
 * request time so the record stays readable if the tenant is later renamed or re-planned.
 *
 * <p>Lifecycle: {@code PENDING} → SuperAdmin {@code APPROVED} (plan activated via
 * {@code TenantPlanApplier.applyPlan}) / {@code REJECTED} (tenant stays put) / tenant-{@code CANCELLED}.
 * Payment happens BEFORE approval — {@code ONLINE} via the existing Razorpay pay-per-invoice path (the
 * linked invoice is settled by the webhook), or {@code OFFLINE} (the tenant supplies a reference and
 * the SuperAdmin marks the linked invoice paid at approval). The linked invoice is issued WITHOUT the
 * pay-first {@code upgradeToPlan} tag and WITHOUT a due date, so settling it neither auto-applies the
 * plan (approval is the sole activation gate) nor trips dunning while the request sits in review.
 */
@Entity
@Table(name = "upgrade_requests",
        indexes = {
                @Index(name = "idx_upgrade_request_tenant", columnList = "tenant_id"),
                @Index(name = "idx_upgrade_request_status", columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class UpgradeRequest extends BaseEntity {

    /** Logical FK to {@code tenants.id} (no JPA association — platform entity). */
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "tenant_code", length = 50)
    private String tenantCode;

    @Column(name = "tenant_name", length = 150)
    private String tenantName;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_plan", length = 20)
    private TenantPlan currentPlan;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_plan", nullable = false, length = 20)
    private TenantPlan requestedPlan;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private UpgradeRequestStatus status = UpgradeRequestStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false, length = 16)
    private PaymentMode paymentMode;

    /** Prorated top-up (ACTIVE tenant) or full month price (non-ACTIVE), pinned at request time. */
    @Column(name = "amount", precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", length = 8)
    @Builder.Default
    private String currency = "INR";

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    // ── Offline payment reference (paymentMode == OFFLINE) ──────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "offline_mode", length = 20)
    private OfflinePaymentMode offlineMode;

    @Column(name = "offline_reference", length = 120)
    private String offlineReference;

    @Column(name = "offline_notes", length = 500)
    private String offlineNotes;

    /** Cloudinary URL of an optional payment proof (receipt/screenshot). */
    @Column(name = "offline_proof_url", length = 500)
    private String offlineProofUrl;

    // ── Linked platform invoice (issued at request time; settled online/offline) ─
    @Column(name = "billing_record_id")
    private Long billingRecordId;

    @Column(name = "billing_record_public_id")
    private UUID billingRecordPublicId;

    // ── Audit ───────────────────────────────────────────────────────────────────
    @Column(name = "requested_by_email", length = 150)
    private String requestedByEmail;

    @Column(name = "reviewed_by_email", length = 150)
    private String reviewedByEmail;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** Rejection reason (REJECTED) or a decision note. */
    @Column(name = "decision_note", length = 500)
    private String decisionNote;
}