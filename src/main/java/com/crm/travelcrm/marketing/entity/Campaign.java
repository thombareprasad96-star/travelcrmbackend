package com.crm.travelcrm.marketing.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.common.entity.Ownable;
import com.crm.travelcrm.marketing.enums.CampaignAudienceType;
import com.crm.travelcrm.marketing.enums.CampaignStatus;
import com.crm.travelcrm.marketing.enums.MarketingChannel;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * A one-shot bulk broadcast (WhatsApp or email) to a segment or all customers.
 * {@code segmentId} is a logical FK (validated in the service); the name is snapshotted.
 */
@Entity
@Table(
        name = "marketing_campaigns",
        indexes = {
                @Index(name = "idx_mkt_campaign_tenant",   columnList = "tenant_id"),
                @Index(name = "idx_mkt_campaign_status",   columnList = "tenant_id,status"),
                @Index(name = "idx_mkt_campaign_sched",    columnList = "tenant_id,status,scheduled_at"),
                @Index(name = "idx_mkt_campaign_owner",    columnList = "tenant_id,owner_user_id"),
                @Index(name = "idx_mkt_campaign_deleted",  columnList = "tenant_id,deleted_at")
        }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class Campaign extends BaseTenantEntity implements Ownable {

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private MarketingChannel channel;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CampaignStatus status = CampaignStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience_type", nullable = false, length = 20)
    private CampaignAudienceType audienceType;

    @Column(name = "segment_id")
    private Long segmentId;

    @Column(name = "segment_public_id")
    private java.util.UUID segmentPublicId;

    @Column(name = "segment_name_snapshot", length = 150)
    private String segmentNameSnapshot;

    @Column(name = "subject", columnDefinition = "TEXT")
    private String subject;

    @Column(name = "body", columnDefinition = "TEXT", nullable = false)
    private String body;

    @Column(name = "template_name", length = 120)
    private String templateName;

    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Builder.Default @Column(name = "total_recipients", nullable = false)
    private int totalRecipients = 0;

    @Builder.Default @Column(name = "sent_count", nullable = false)
    private int sentCount = 0;

    @Builder.Default @Column(name = "failed_count", nullable = false)
    private int failedCount = 0;
}