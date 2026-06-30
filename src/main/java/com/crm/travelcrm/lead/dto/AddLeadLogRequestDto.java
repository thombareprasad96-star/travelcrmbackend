package com.crm.travelcrm.lead.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * POST /api/leads/{publicId}/logs body. Mirrors the frontend AddLogModal payload.
 */
@Data
public class AddLeadLogRequestDto {

    @NotBlank(message = "Log comment is required")
    @Size(min = 5, max = 2000, message = "Comment must be between 5 and 2000 characters")
    private String comment;

    /** When true a follow-up Reminder is also created (requires {@link #followUpDate}). */
    private boolean createReminder;

    /** Required only when {@link #createReminder} is true. */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate followUpDate;

    /**
     * Informational stage label sent by the frontend. The server snapshots the lead's ACTUAL
     * current stage onto the log, so this value is accepted but not trusted.
     */
    private String stage;
}