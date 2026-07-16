package com.crm.travelcrm.subagent.license.entity;

import com.crm.travelcrm.common.entity.BaseEntity;
import com.crm.travelcrm.platform.subscription.upgrade.enums.OfflinePaymentMode;
import com.crm.travelcrm.platform.subscription.upgrade.enums.PaymentMode;
import com.crm.travelcrm.subagent.license.enums.SubAgentLicenseRequestStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A tenant-initiated request to PURCHASE sub-agent (Travel Partner) seat licenses, which a SuperAdmin
 * must review and approve before the seats are granted and the pending sub-agent(s) activate.
 *
 * <p><b>Platform-level</b> (extends {@link BaseEntity}, not {@code BaseTenantEntity}) — exactly like
 * {@code UpgradeRequest} and {@code BillingRecord}: it is reviewed by a SuperAdmin who has no
 * {@code TenantContext}, so it must never be caught by the tenant {@code @Filter}. The owning tenant is
 * referenced by the logical FK {@code tenantId}; tenant/sub-agent identity is snapshotted at request
 * time so the record stays readable later.
 *
 * <p>This is the seat-add-on sibling of {@code UpgradeRequest} — the two surface together in the one
 * SuperAdmin approval queue. Lifecycle: {@code PENDING} → SuperAdmin {@code APPROVED} (seat cap raised
 * additively + the linked {@code PENDING_LICENSE} sub-agent activated) / {@code REJECTED} (sub-agent
 * stays pending; tenant may resubmit) / tenant-{@code CANCELLED}. Payment happens BEFORE approval, via
 * the same untagged/no-due-date invoice pattern (online Razorpay webhook, or offline mark-paid at
 * approval): settling it neither grants seats (approval is the sole gate) nor trips dunning.
 */
@Entity
@Table(name = "sub_agent_license_requests",
        indexes = {
                @Index(name = "idx_sa_license_req_tenant", columnList = "tenant_id"),
                @Index(name = "idx_sa_license_req_status", columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class SubAgentLicenseRequest extends BaseEntity {

    /** Logical FK to {@code tenants.id} (no JPA association — platform entity). */
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "tenant_code", length = 50)
    private String tenantCode;

    @Column(name = "tenant_name", length = 150)
    private String tenantName;

    // ── The PENDING_LICENSE sub-agent this purchase will activate on approval ──
    /** Logical FK to {@code sub_agent_profiles.id}. */
    @Column(name = "sub_agent_profile_id")
    private Long subAgentProfileId;

    @Column(name = "sub_agent_profile_public_id")
    private UUID subAgentProfilePublicId;

    @Column(name = "sub_agent_name", length = 150)
    private String subAgentName;

    @Column(name = "sub_agent_email", length = 150)
    private String subAgentEmail;

    // ── Seat pricing (one-time unlock) ──
    /** Number of seats purchased by this request (v1 = 1 per sub-agent). */
    @Column(name = "quantity", nullable = false)
    @Builder.Default
    private int quantity = 1;

    /** One-time per-seat unlock rate, pinned at request time. */
    @Column(name = "unit_price", precision = 12, scale = 2)
    private BigDecimal unitPrice;

    /** quantity × unitPrice, pinned at request time. */
    @Column(name = "amount", precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", length = 8)
    @Builder.Default
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private SubAgentLicenseRequestStatus status = SubAgentLicenseRequestStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false, length = 16)
    private PaymentMode paymentMode;

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