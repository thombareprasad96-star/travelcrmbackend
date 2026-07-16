package com.crm.travelcrm.marketing.dto;

import com.crm.travelcrm.marketing.enums.MarketingChannel;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * A drip step. On requests {@code publicId} is ignored (steps are replaced wholesale);
 * on responses it identifies the persisted step.
 */
public record DripStepDTO(
        UUID publicId,
        int stepOrder,
        String name,
        @Min(value = 0, message = "Delay days cannot be negative") int delayDays,
        @NotNull(message = "Step channel is required") MarketingChannel channel,
        String subject,
        @NotBlank(message = "Step message body is required") String body,
        String templateName
) {}