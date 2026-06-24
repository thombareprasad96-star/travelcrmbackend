package com.crm.travelcrm.company.dto;

import lombok.Builder;
import lombok.Value;

// Placeholder AI-credit info — no metering system yet; returns defaults.
@Value
@Builder
public class AiCreditsDTO {
    Integer used;
    Integer total;
    Double  usedCost;
}