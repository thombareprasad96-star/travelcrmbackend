package com.crm.travelcrm.settings.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WhatsAppConfigRequest {

    private boolean apiKeyChanged;          // true = a new API key is included

    private String apiKey;                  // present only when apiKeyChanged=true

    @NotBlank(message = "Template Name is required")
    private String templateName;

    @NotBlank(message = "Template Language is required")
    private String templateLanguage;

    private String headerImageUrl;          // optional
}