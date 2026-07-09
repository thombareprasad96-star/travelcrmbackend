package com.crm.travelcrm.platform.entitlement.dto;

import lombok.Data;

import java.util.Set;

/** Set a tenant's enabled modules (SuperAdmin). Unknown keys are ignored server-side. */
@Data
public class UpdateModulesRequest {
    private Set<String> modules;
}