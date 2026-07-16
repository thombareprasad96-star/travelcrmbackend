package com.crm.travelcrm.marketing.dto;

import com.crm.travelcrm.marketing.enums.MarketingChannel;
import com.crm.travelcrm.marketing.enums.RecipientStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record CampaignRecipientResponse(
        UUID publicId,
        String customerName,
        String destination,
        MarketingChannel channel,
        RecipientStatus status,
        String error,
        LocalDateTime sentAt
) {}