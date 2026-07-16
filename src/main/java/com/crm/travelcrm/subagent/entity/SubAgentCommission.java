package com.crm.travelcrm.subagent.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One entry in a sub-agent's broker-commission ledger. Each row is a signed DELTA: a positive amount
 * is commission earned (as the customer pays down a booking), a negative amount is a reversal (a
 * receipt was deleted or the booking was refunded). A booking's currently-accrued commission is the
 * SUM of its rows, so the ledger self-reconciles as the booking's net collection changes over time.
 *
 * <p>Commission accrues ON PAYMENT RECEIVED, proportional to net collection
 * ({@code paidAmount − refundedAmount) / totalPayable}) — see {@code SubAgentCommissionService}. The
 * sub-agent keeps this share; the parent tenant keeps the rest.</p>
 *
 * <p>Tenant-scoped ({@link BaseTenantEntity}); {@code tenantId} auto-stamped on persist.</p>
 */
@Entity
@Table(name = "sub_agent_commissions", indexes = {
        @Index(name = "idx_sacomm_tenant_agent", columnList = "tenant_id,sub_agent_user_id"),
        @Index(name = "idx_sacomm_booking", columnList = "booking_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class SubAgentCommission extends BaseTenantEntity {

    /** The booking that generated the commission (bookings.id — logical FK, application-enforced). */
    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    /** Snapshot of the booking code for display in the ledger without joining bookings. */
    @Column(name = "booking_code", length = 40)
    private String bookingCode;

    /** The sub-agent User who earns the commission (users.id, role = SUB_AGENT). */
    @Column(name = "sub_agent_user_id", nullable = false)
    private Long subAgentUserId;

    /** Signed delta: positive = earned, negative = reversed. Running accrued = SUM over the booking. */
    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    /** Why this delta was recorded (e.g. "Accrual on payment", "Reversal on refund/receipt removal"). */
    @Column(name = "note", length = 200)
    private String note;

    /** The date the underlying money movement occurred. */
    @Column(name = "occurred_on")
    private LocalDate occurredOn;
}
