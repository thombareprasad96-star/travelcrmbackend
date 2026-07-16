package com.crm.travelcrm.platform.config.service;

import com.crm.travelcrm.platform.config.dto.MaintenanceStatusResponse;
import com.crm.travelcrm.platform.config.dto.PlatformConfigResponse;

import java.util.List;

/** Platform-wide key/value config + maintenance mode. */
public interface PlatformConfigService {

    // Well-known keys.
    String MAINTENANCE_ENABLED = "maintenance.enabled";
    String MAINTENANCE_MESSAGE = "maintenance.message";
    /** Platform-flat default monthly seat fee (in the plan currency) charged per active sub-agent. */
    String SUBAGENT_SEAT_FEE = "billing.subagent.seat-fee";
    /**
     * Platform-flat default ONE-TIME seat-license (unlock) fee charged per sub-agent seat purchased.
     * Distinct from the recurring {@link #SUBAGENT_SEAT_FEE}; when unset the license fee falls back to
     * the recurring seat rate (see {@code SubAgentSeatFeeService#effectiveLicenseRate}).
     */
    String SUBAGENT_SEAT_LICENSE_FEE = "billing.subagent.seat-license-fee";

    List<PlatformConfigResponse> listConfig();

    PlatformConfigResponse setConfig(String key, String value, String description);

    /** Cached read (never hits the DB) — used by the per-request maintenance gate. */
    String get(String key, String defaultValue);

    boolean isMaintenanceEnabled();

    String maintenanceMessage();

    MaintenanceStatusResponse maintenanceStatus();

    MaintenanceStatusResponse setMaintenance(boolean enabled, String message);
}