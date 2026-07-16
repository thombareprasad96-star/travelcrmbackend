package com.crm.travelcrm.lead.assignment.strategy;

import java.util.Optional;

/**
 * Strategy for recommending the assignee of a new lead. Each implementation declares the
 * {@link AssignmentStrategyType} it handles; {@link LeadAssignmentStrategyResolver} routes to it.
 * Add a new strategy (destination-based, skill-based, AI, …) by dropping in a new bean — the
 * lead-creation flow is untouched (Open/Closed Principle), mirroring {@code OtpDeliverySender}.
 *
 * <p>Implementations MUST be pure: given the same {@link AssignmentContext} they return the same
 * recommendation, with no DB access or side effects (persistence — e.g. advancing the round-robin
 * cursor — is the orchestrating service's job, done once at commit time).
 */
public interface LeadAssignmentStrategy {

    /** The strategy this bean implements (its routing key). */
    AssignmentStrategyType type();

    /**
     * The recommended assignee's internal user id, or empty when this strategy cannot recommend
     * anyone for the given context (e.g. an empty candidate pool, or MANUAL with no explicit choice).
     */
    Optional<Long> recommend(AssignmentContext ctx);
}