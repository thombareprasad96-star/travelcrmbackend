package com.crm.travelcrm.marketing.dto;

import com.crm.travelcrm.marketing.enums.AutomationType;
import com.crm.travelcrm.marketing.enums.MarketingChannel;

import java.time.LocalDate;
import java.util.UUID;

public record UpcomingCelebrationResponse(
        UUID customerPublicId,
        String customerName,
        LocalDate date,
        long daysAway,
        AutomationType triggerType,
        MarketingChannel channel,
        boolean reachable
) {}