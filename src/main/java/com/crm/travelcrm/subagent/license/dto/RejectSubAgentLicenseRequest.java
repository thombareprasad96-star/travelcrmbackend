package com.crm.travelcrm.subagent.license.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** SuperAdmin rejects a sub-agent seat-license request; the reason is surfaced to the tenant. */
@Data
public class RejectSubAgentLicenseRequest {

    @NotBlank(message = "A rejection reason is required")
    @Size(max = 500, message = "Reason cannot exceed 500 characters")
    private String reason;
}