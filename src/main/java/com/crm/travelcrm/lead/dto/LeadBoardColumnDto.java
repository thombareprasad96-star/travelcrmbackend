package com.crm.travelcrm.lead.dto;

import com.crm.travelcrm.lead.enums.LeadStage;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * One Kanban column: a pipeline stage with the leads currently in it plus
 * pre-computed roll-ups so the frontend doesn't have to recalculate.
 *
 * The board endpoint returns all seven {@link LeadStage} columns in pipeline
 * order, including any that are empty, so the UI can render every lane.
 */
@Data
@Builder
public class LeadBoardColumnDto {

    /** Serialized as the display name (e.g. "New Lead") via {@code @JsonValue}. */
    private LeadStage stage;

    /** Number of leads in this column (matches {@code leads.size()}). */
    private long count;

    /** Sum of {@code estimatedValue} across the leads in this column. */
    private BigDecimal totalValue;

    private List<LeadResponseDto> leads;

}
