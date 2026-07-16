package com.crm.travelcrm.marketing.dto;

import com.crm.travelcrm.marketing.enums.CampaignAudienceType;
import com.crm.travelcrm.marketing.enums.CampaignStatus;
import com.crm.travelcrm.marketing.enums.MarketingChannel;

import java.time.LocalDateTime;
import java.util.UUID;

public record CampaignResponse(
        UUID publicId,
        String name,
        MarketingChannel channel,
        CampaignStatus status,
        CampaignAudienceType audienceType,
        UUID segmentPublicId,
        String segmentName,
        String subject,
        String body,
        String templateName,
        LocalDateTime scheduledAt,
        LocalDateTime sentAt,
        int totalRecipients,
        int sentCount,
        int failedCount,
        String ownerName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}