package com.crm.travelcrm.marketing.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.marketing.enums.EnrollmentStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * A single customer's progress through a drip sequence. The runner processes ACTIVE
 * enrollments whose {@code nextRunAt} is due, sends the current step, then advances.
 */
@Entity
@Table(
        name = "marketing_drip_enrollments",
        indexes = {
                @Index(name = "idx_mkt_enroll_sequence", columnList = "tenant_id,sequence_id"),
                @Index(name = "idx_mkt_enroll_due",      columnList = "tenant_id,status,next_run_at"),
                @Index(name = "idx_mkt_enroll_customer", columnList = "tenant_id,sequence_id,customer_id")
        }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class DripEnrollment extends BaseTenantEntity {

    @Column(name = "sequence_id", nullable = false)
    private Long sequenceId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "customer_name_snapshot", length = 200)
    private String customerNameSnapshot;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EnrollmentStatus status = EnrollmentStatus.ACTIVE;

    @Builder.Default @Column(name = "current_step_order", nullable = false)
    private int currentStepOrder = 1;

    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @Column(name = "enrolled_at")
    private LocalDateTime enrolledAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}