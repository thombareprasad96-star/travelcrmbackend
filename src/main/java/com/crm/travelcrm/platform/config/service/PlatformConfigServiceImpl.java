package com.crm.travelcrm.platform.config.service;

import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import com.crm.travelcrm.platform.config.dto.MaintenanceStatusResponse;
import com.crm.travelcrm.platform.config.dto.PlatformConfigResponse;
import com.crm.travelcrm.platform.config.entity.PlatformConfig;
import com.crm.travelcrm.platform.config.repository.PlatformConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformConfigServiceImpl implements PlatformConfigService {

    private static final String DEFAULT_MAINTENANCE_MESSAGE =
            "The application is temporarily down for maintenance. Please try again shortly.";

    private final PlatformConfigRepository repository;
    private final PlatformAuditRecorder platformAuditRecorder;

    // Read-through cache so the per-request maintenance gate never touches the DB (single-node;
    // a multi-node deployment would add a refresh/pub-sub — see build notes).
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    @PostConstruct
    void warmCache() {
        try {
            repository.findAllByDeletedAtIsNullOrderByKeyAsc()
                    .forEach(c -> cache.put(c.getKey(), c.getValue() == null ? "" : c.getValue()));
            log.info("[PlatformConfig] warmed {} config entries", cache.size());
        } catch (Exception ex) {
            log.warn("[PlatformConfig] cache warm failed (defaults apply): {}", ex.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlatformConfigResponse> listConfig() {
        return repository.findAllByDeletedAtIsNullOrderByKeyAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public PlatformConfigResponse setConfig(String key, String value, String description) {
        PlatformConfig saved = upsert(key, value, description);
        platformAuditRecorder.safeRecord(PlatformAuditAction.CONFIG_CHANGE, true,
                null, null, "CONFIG", saved.getPublicId(), "Set " + key + " = " + value);
        return toResponse(saved);
    }

    @Override
    public String get(String key, String defaultValue) {
        String v = cache.get(key);
        return v != null ? v : defaultValue;
    }

    @Override
    public boolean isMaintenanceEnabled() {
        return Boolean.parseBoolean(get(MAINTENANCE_ENABLED, "false"));
    }

    @Override
    public String maintenanceMessage() {
        String m = get(MAINTENANCE_MESSAGE, "");
        return (m == null || m.isBlank()) ? DEFAULT_MAINTENANCE_MESSAGE : m;
    }

    @Override
    public MaintenanceStatusResponse maintenanceStatus() {
        return new MaintenanceStatusResponse(isMaintenanceEnabled(), maintenanceMessage());
    }

    @Override
    @Transactional
    public MaintenanceStatusResponse setMaintenance(boolean enabled, String message) {
        PlatformConfig flag = upsert(MAINTENANCE_ENABLED, String.valueOf(enabled),
                "Tenant-app maintenance mode (blocks all tenant traffic with 503)");
        if (message != null) {
            upsert(MAINTENANCE_MESSAGE, message, "Message shown to tenants during maintenance");
        }
        platformAuditRecorder.safeRecord(PlatformAuditAction.MAINTENANCE_TOGGLE, true,
                null, null, "CONFIG", flag.getPublicId(),
                "Maintenance " + (enabled ? "ENABLED" : "DISABLED")
                        + (message != null && !message.isBlank() ? " — " + message : ""));
        return maintenanceStatus();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private PlatformConfig upsert(String key, String value, String description) {
        PlatformConfig cfg = repository.findByKey(key)
                .orElseGet(() -> PlatformConfig.builder().key(key).build());
        cfg.setValue(value);
        if (description != null) {
            cfg.setDescription(description);
        }
        PlatformConfig saved = repository.save(cfg);
        cache.put(key, value == null ? "" : value);
        return saved;
    }

    private PlatformConfigResponse toResponse(PlatformConfig c) {
        return new PlatformConfigResponse(c.getPublicId(), c.getKey(), c.getValue(), c.getDescription());
    }
}