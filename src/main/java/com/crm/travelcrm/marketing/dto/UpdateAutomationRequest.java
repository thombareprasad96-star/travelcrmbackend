package com.crm.travelcrm.marketing.dto;

import com.crm.travelcrm.marketing.enums.MarketingChannel;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;

public record UpdateAutomationRequest(
        boolean enabled,
        @NotNull(message = "Channel is required") MarketingChannel channel,
        @Min(value = 0, message = "Days-before cannot be negative") @Max(value = 60, message = "Days-before is too large") int daysBefore,
        @NotNull(message = "Send time is required") @JsonFormat(pattern = "HH:mm") LocalTime sendTime,
        String subject,
        @NotBlank(message = "Message body is required") String body,
        @Size(max = 120) String templateName
) {}