package com.crm.travelcrm.platform.config.service;

import com.crm.travelcrm.platform.config.dto.MaintenanceStatusResponse;
import com.crm.travelcrm.platform.config.dto.PlatformConfigResponse;

import java.util.List;

/** Platform-wide key/value config + maintenance mode. */
public interface PlatformConfigService {

    // Well-known keys.
    String MAINTENANCE_ENABLED = "maintenance.enabled";
    String MAINTENANCE_MESSAGE = "maintenance.message";

    List<PlatformConfigResponse> listConfig();

    PlatformConfigResponse setConfig(String key, String value, String description);

    /** Cached read (never hits the DB) — used by the per-request maintenance gate. */
    String get(String key, String defaultValue);

    boolean isMaintenanceEnabled();

    String maintenanceMessage();

    MaintenanceStatusResponse maintenanceStatus();

    MaintenanceStatusResponse setMaintenance(boolean enabled, String message);
}