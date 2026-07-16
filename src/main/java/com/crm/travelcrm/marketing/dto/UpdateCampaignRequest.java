package com.crm.travelcrm.marketing.dto;

import com.crm.travelcrm.marketing.enums.CampaignAudienceType;
import com.crm.travelcrm.marketing.enums.MarketingChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.UUID;

public record UpdateCampaignRequest(
        @NotBlank(message = "Campaign name is required") @Size(max = 150) String name,
        @NotNull(message = "Channel is required") MarketingChannel channel,
        @NotNull(message = "Audience type is required") CampaignAudienceType audienceType,
        UUID segmentPublicId,
        String subject,
        @NotBlank(message = "Message body is required") String body,
        @Size(max = 120) String templateName,
        LocalDateTime scheduledAt
) {}