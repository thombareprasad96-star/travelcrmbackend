package com.crm.travelcrm.lead.dto;

import com.crm.travelcrm.lead.enums.LeadStage;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Lightweight payload for drag-and-drop on the Kanban board — moves a lead to
 * a new stage without touching any other field (and without the duplicate
 * checks that the full update path runs).
 *
 * Accepts either the display name ("New Lead") or the enum name ("NEW_LEAD");
 * {@link LeadStage#fromValue} handles both.
 */
@Data
public class UpdateLeadStageRequestDto {

    @NotNull(message = "Lead stage is required")
    private LeadStage leadStage;
}