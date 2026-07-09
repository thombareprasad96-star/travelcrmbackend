package com.crm.travelcrm.platform.config.dto;

/** Current maintenance-mode state for the tenant app. */
public record MaintenanceStatusResponse(boolean enabled, String message) {
}