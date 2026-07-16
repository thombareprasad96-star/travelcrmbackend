package com.crm.travelcrm.marketing.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.marketing.enums.MarketingChannel;
import com.crm.travelcrm.marketing.enums.RecipientStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * One materialised send target for a campaign. Created when the campaign is dispatched;
 * the dispatch loop is resumable, so these carry a durable per-recipient status.
 */
@Entity
@Table(
        name = "marketing_campaign_recipients",
        indexes = {
                @Index(name = "idx_mkt_recipient_campaign", columnList = "tenant_id,campaign_id"),
                @Index(name = "idx_mkt_recipient_status",   columnList = "campaign_id,status")
        }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class CampaignRecipient extends BaseTenantEntity {

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "customer_name_snapshot", length = 200)
    private String customerNameSnapshot;

    @Column(name = "destination", length = 200)
    private String destination;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private MarketingChannel channel;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RecipientStatus status = RecipientStatus.PENDING;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;
}