package com.crm.travelcrm.marketing.dto;

import jakarta.validation.constraints.NotBlank;

/** A single test send target — a phone number (WhatsApp) or email address. */
public record SendTestRequest(
        @NotBlank(message = "A destination phone or email is required") String to
) {}