package com.crm.travelcrm.report.followup.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** One row of the Follow-up Report table (derived from a {@code Reminder} + its {@code Lead}). */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FollowupTaskDTO {
    private UUID    publicId;             // reminder publicId (external id)
    private String  dueDate;             // "Jun 01, 2026"
    private String  dueTime;             // "20:07"
    private String  overdueBy;           // "Overdue by 21 days" | "Upcoming"
    private String  leadName;
    private String  leadPhone;
    private String  leadTemp;            // lead type label (closest to "temperature")
    private String  assignedTo;          // assignee full name
    private String  assignedUsername;    // "@deepti_paul"
    private UUID    assignedUserPublicId;
    private String  type;                // reminder type, e.g. "First Contact"
    private String  priority;            // High | Medium | Low
    private String  title;
    private String  desc;
    private String  stage;               // lead stage display
    private String  travelDate;          // "Jul 07" | "N/A"
    private boolean completed;
}