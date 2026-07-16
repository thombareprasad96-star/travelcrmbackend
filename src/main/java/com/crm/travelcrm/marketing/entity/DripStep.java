package com.crm.travelcrm.marketing.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.marketing.enums.MarketingChannel;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * A single step in a drip sequence. {@code delayDays} is the wait after the previous
 * step completes (step 1 = wait after enrollment).
 */
@Entity
@Table(
        name = "marketing_drip_steps",
        indexes = {
                @Index(name = "idx_mkt_step_sequence", columnList = "tenant_id,sequence_id,step_order")
        }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class DripStep extends BaseTenantEntity {

    @Column(name = "sequence_id", nullable = false)
    private Long sequenceId;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Column(name = "name", length = 150)
    private String name;

    @Builder.Default @Column(name = "delay_days", nullable = false)
    private int delayDays = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private MarketingChannel channel;

    @Column(name = "subject", columnDefinition = "TEXT")
    private String subject;

    @Column(name = "body", columnDefinition = "TEXT", nullable = false)
    private String body;

    @Column(name = "template_name", length = 120)
    private String templateName;
}