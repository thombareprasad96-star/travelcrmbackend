package com.crm.travelcrm.lead.assignment.strategy;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Routes to the right {@link LeadAssignmentStrategy} for a {@link AssignmentStrategyType}. It
 * auto-discovers every strategy bean into an {@link EnumMap}, so registering a new strategy wires it
 * in with no edits here (mirrors {@code OtpSenderResolver}).
 */
@Component
public class LeadAssignmentStrategyResolver {

    private final Map<AssignmentStrategyType, LeadAssignmentStrategy> byType =
            new EnumMap<>(AssignmentStrategyType.class);

    public LeadAssignmentStrategyResolver(List<LeadAssignmentStrategy> strategies) {
        for (LeadAssignmentStrategy strategy : strategies) {
            byType.put(strategy.type(), strategy);
        }
    }

    public LeadAssignmentStrategy resolve(AssignmentStrategyType type) {
        LeadAssignmentStrategy strategy = byType.get(type);
        if (strategy == null) {
            throw new IllegalStateException("No lead-assignment strategy registered for " + type);
        }
        return strategy;
    }
}