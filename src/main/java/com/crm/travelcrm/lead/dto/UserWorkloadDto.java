package com.crm.travelcrm.lead.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

/**
 * One row of the user workload dashboard — populated directly by a JPQL
 * constructor expression in {@code LeadRepository.findUserWorkload}, so the
 * count is computed in the database (no leads are loaded into memory).
 */
@Data
@AllArgsConstructor
public class UserWorkloadDto {
    private UUID userId;        // publicId — never the internal Long id
    private String fullName;
    private String email;
    private long totalLeads;
}