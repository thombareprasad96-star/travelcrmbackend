package com.crm.travelcrm.lead.assignment.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Drives the "Assign To" control on the create-lead form. Two shapes:
 *
 * <ul>
 *   <li><b>forcedSelf = true</b> (agents / staff / sub-agents): the UI hides the dropdown and assigns
 *       the lead to {@code self}. {@code recommendedUserId} equals the self publicId. {@code strategy}
 *       is {@code SELF}.</li>
 *   <li><b>forcedSelf = false</b> (tenant admin / manager): the UI populates the dropdown from
 *       {@code eligibleUsers}, pre-fills {@code recommendedUserId}, and shows the
 *       "Recommended by Load-Based Assignment" badge — but the choice stays editable. {@code strategy}
 *       is {@code LOAD_BASED}.</li>
 * </ul>
 *
 * <p>This endpoint is a read-only <b>suggestion</b>: it never assigns anything and never advances the
 * round-robin cursor. The authoritative decision (and the audit + cursor advance) happens at lead
 * creation.
 */
@Data
@Builder
public class AssignmentRecommendationResponse {

    /** {@code AssignmentStrategyType} name of the mode the UI should apply: {@code SELF} or {@code LOAD_BASED}. */
    private String strategy;

    /** Human-readable label for the badge, e.g. "Load-Based Assignment". */
    private String strategyLabel;

    /** True → hide the dropdown and force self-assignment. */
    private boolean forcedSelf;

    /** The current user (for the forced-self case, and so the UI can show "assigned to you"). */
    private EligibleUserDto self;

    /** publicId to pre-select in the dropdown (the recommended assignee, or self when forcedSelf). Nullable. */
    private UUID recommendedUserId;

    /** Display name of the recommended assignee, for the badge tooltip. Nullable. */
    private String recommendedUserName;

    /** The assignable pool for the dropdown (empty when forcedSelf). */
    @Builder.Default
    private List<EligibleUserDto> eligibleUsers = List.of();
}