package com.crm.travelcrm.lead.dto;

import com.crm.travelcrm.lead.enums.LeadStage;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

/**
 * Lead count for one (user, stage) pair — populated by a JPQL constructor
 * expression with GROUP BY in {@code LeadRepository.countLeadsByStagePerUser}.
 */
@Data
@AllArgsConstructor
public class UserLeadStageCountDto {
    private UUID userId;        // publicId
    private String fullName;
    private LeadStage stage;
    private long leadCount;
}