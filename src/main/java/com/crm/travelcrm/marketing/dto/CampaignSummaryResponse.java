package com.crm.travelcrm.marketing.dto;

public record CampaignSummaryResponse(
        long total,
        long drafts,
        long scheduled,
        long sent,
        long messagesSentThisMonth,
        long audienceReachable
) {}