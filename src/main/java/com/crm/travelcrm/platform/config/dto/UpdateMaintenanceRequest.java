package com.crm.travelcrm.platform.config.dto;

import lombok.Data;

/** Toggle tenant-app maintenance mode (SuperAdmin). */
@Data
public class UpdateMaintenanceRequest {
    private Boolean enabled;
    private String message;
}