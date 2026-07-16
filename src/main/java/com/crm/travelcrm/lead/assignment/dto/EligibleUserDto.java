package com.crm.travelcrm.lead.assignment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

/**
 * One assignable user for the create-lead "Assign To" dropdown. Exposes the publicId only (never the
 * internal Long id) and carries the user's current active-lead load so the UI can show it and the
 * recommendation is explainable.
 */
@Data
@AllArgsConstructor
public class EligibleUserDto {
    private UUID id;          // publicId — matches CreateLeadRequestDto.assignedUserId
    private String name;
    private String email;
    private long activeLeads; // current active-lead count (the load the recommendation ranks on)
}