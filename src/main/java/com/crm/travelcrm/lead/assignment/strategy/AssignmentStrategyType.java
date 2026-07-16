package com.crm.travelcrm.lead.assignment.strategy;

/**
 * The lead-assignment strategies. Each {@link LeadAssignmentStrategy} bean self-declares the type it
 * handles and is auto-discovered by {@link LeadAssignmentStrategyResolver} (same open-for-extension
 * pattern as {@code OtpSenderResolver}). Adding a future strategy — destination-based,
 * department-based, branch-based, skill-based, AI-recommendation — is a new enum constant + a new
 * bean, with no change to the lead-creation flow.
 */
public enum AssignmentStrategyType {

    /** A human explicitly chose the assignee (e.g. an admin overriding the recommendation). */
    MANUAL,

    /** The creator is force-assigned to themselves (agents / staff / sub-agents). */
    SELF,

    /** Rotate through the eligible users in a stable order, ignoring their current load. */
    ROUND_ROBIN,

    /** Pick the eligible user with the fewest active leads; ties broken by {@link #ROUND_ROBIN}. */
    LOAD_BASED
}