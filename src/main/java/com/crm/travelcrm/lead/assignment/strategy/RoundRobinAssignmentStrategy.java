package com.crm.travelcrm.lead.assignment.strategy;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Pure round-robin: rotate through the eligible users in a stable order, ignoring their current load.
 * Kept as a first-class strategy for future explicit selection; the load-based strategy reuses the
 * same {@link RoundRobinRotation} helper to break ties.
 */
@Component
public class RoundRobinAssignmentStrategy implements LeadAssignmentStrategy {

    @Override
    public AssignmentStrategyType type() {
        return AssignmentStrategyType.ROUND_ROBIN;
    }

    @Override
    public Optional<Long> recommend(AssignmentContext ctx) {
        return Optional.ofNullable(
                RoundRobinRotation.pickNext(ctx.getCandidateUserIds(), ctx.getLastAssignedUserId()));
    }
}