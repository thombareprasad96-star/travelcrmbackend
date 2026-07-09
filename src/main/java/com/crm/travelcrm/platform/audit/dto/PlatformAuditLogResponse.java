package com.crm.travelcrm.platform.audit.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A single audit-ledger row for the console. Whitelisted — the acting SuperAdmin and target
 * tenant are exposed only via their readable snapshots (email / org code) and external ids,
 * never internal Long PKs.
 */
public record PlatformAuditLogResponse(
        UUID publicId,
        String action,
        String actorEmail,
        String targetTenantCode,
        String targetType,
        UUID targetPublicId,
        boolean success,
        String description,
        String ipAddress,
        LocalDateTime createdAt) {
}