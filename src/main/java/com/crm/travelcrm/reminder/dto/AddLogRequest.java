package com.crm.travelcrm.reminder.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * POST /api/reminders/{id}/logs body: {@code { "log": "Called customer, no answer." }}.
 */
@Getter
@Setter
public class AddLogRequest {

    @NotBlank
    private String log;
}