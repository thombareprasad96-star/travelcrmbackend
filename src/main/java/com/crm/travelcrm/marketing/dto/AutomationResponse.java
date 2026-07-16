package com.crm.travelcrm.marketing.dto;

import com.crm.travelcrm.marketing.enums.AutomationType;
import com.crm.travelcrm.marketing.enums.MarketingChannel;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalTime;

public record AutomationResponse(
        AutomationType triggerType,
        boolean enabled,
        MarketingChannel channel,
        int daysBefore,
        @JsonFormat(pattern = "HH:mm") LocalTime sendTime,
        String subject,
        String body,
        String templateName,
        LocalDate lastRunOn,
        long totalSent,
        long upcomingCount
) {}