package com.crm.travelcrm.lead.assignment.strategy;

import org.springframework.stereotype.Component;

import java.util.Optional;

/** Recommends whoever was explicitly requested — used when a human overrides the recommendation. */
@Component
public class ManualAssignmentStrategy implements LeadAssignmentStrategy {

    @Override
    public AssignmentStrategyType type() {
        return AssignmentStrategyType.MANUAL;
    }

    @Override
    public Optional<Long> recommend(AssignmentContext ctx) {
        return Optional.ofNullable(ctx.getRequestedAssigneeUserId());
    }
}