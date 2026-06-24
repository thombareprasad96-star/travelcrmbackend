package com.crm.travelcrm.company.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

// Placeholder subscription info — there is no billing system yet, so the service
// returns sensible defaults. Replace with a real subscription source when added.
@Value
@Builder
public class SubscriptionDTO {
    String       plan;
    String       startDate;
    String       endDate;
    String       status;
    Integer      daysLeft;
    List<String> features;
}