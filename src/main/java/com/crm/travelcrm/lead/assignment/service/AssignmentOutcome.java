package com.crm.travelcrm.lead.assignment.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.lead.assignment.strategy.AssignmentStrategyType;

/**
 * The authoritative result of deciding a new lead's owner at creation time — everything the caller
 * needs to set the assignee AND write the audit row.
 *
 * @param finalUser             the user the lead is actually assigned to (never null)
 * @param recommendedUserId     internal id of what the strategy recommended (null if none)
 * @param recommendedUserName   denormalised name of the recommended user (null if none)
 * @param strategyUsed          the strategy that produced the FINAL assignment
 * @param manualOverride        true when the final assignee differs from the recommendation
 */
public record AssignmentOutcome(
        User finalUser,
        Long recommendedUserId,
        String recommendedUserName,
        AssignmentStrategyType strategyUsed,
        boolean manualOverride) {
}