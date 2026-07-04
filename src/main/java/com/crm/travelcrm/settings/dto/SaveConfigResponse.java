package com.crm.travelcrm.settings.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Shared save-confirmation payload for both Email and WhatsApp config saves. */
@Data
@AllArgsConstructor
public class SaveConfigResponse {
    private boolean success;
    private String  message;
    private String  savedAt;    // "Jun 25, 2026 10:15"
}