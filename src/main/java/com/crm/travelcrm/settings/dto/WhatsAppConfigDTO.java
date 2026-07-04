package com.crm.travelcrm.settings.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** WhatsApp config sent to the UI. The API key is NEVER returned — only {@code apiKeySet}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WhatsAppConfigDTO {
    private boolean configured;
    private boolean apiKeySet;      // true = an API key is stored (value never returned)
    private String  templateName;
    private String  templateLanguage;
    private String  headerImageUrl;
    private String  whatsAppPhone;
    private String  lastTestedAt;
    private String  lastSavedAt;
}