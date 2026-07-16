package com.crm.travelcrm.marketing.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.marketing.enums.AutomationType;
import com.crm.travelcrm.marketing.enums.MarketingChannel;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Tenant-level config for a recurring date trigger (birthday / anniversary). Exactly
 * one row per {@code (tenant, triggerType)} — auto-provisioned disabled on first read.
 * {@code lastRunOn} makes the daily job idempotent.
 */
@Entity
@Table(
        name = "marketing_automation_triggers",
        indexes = {
                @Index(name = "idx_mkt_auto_tenant", columnList = "tenant_id,trigger_type")
        }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class AutomationTrigger extends BaseTenantEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private AutomationType triggerType;

    @Builder.Default @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private MarketingChannel channel = MarketingChannel.WHATSAPP;

    @Builder.Default @Column(name = "days_before", nullable = false)
    private int daysBefore = 0;

    @Builder.Default @Column(name = "send_time", nullable = false)
    private LocalTime sendTime = LocalTime.of(9, 0);

    @Column(name = "subject", columnDefinition = "TEXT")
    private String subject;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Column(name = "template_name", length = 120)
    private String templateName;

    @Column(name = "last_run_on")
    private LocalDate lastRunOn;

    @Builder.Default @Column(name = "total_sent", nullable = false)
    private long totalSent = 0;
}