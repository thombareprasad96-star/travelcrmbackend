package com.crm.travelcrm.lead.assignment.strategy;

import org.springframework.stereotype.Component;

import java.util.Optional;

/** Recommends the creator themselves — the forced strategy for agents, staff and sub-agents. */
@Component
public class SelfAssignmentStrategy implements LeadAssignmentStrategy {

    @Override
    public AssignmentStrategyType type() {
        return AssignmentStrategyType.SELF;
    }

    @Override
    public Optional<Long> recommend(AssignmentContext ctx) {
        return Optional.ofNullable(ctx.getCurrentUserId());
    }
}