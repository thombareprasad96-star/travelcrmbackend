package com.crm.travelcrm.fleet.dto;

import com.crm.travelcrm.fleet.enums.FleetDocumentCategory;
import com.crm.travelcrm.fleet.enums.FleetRefType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Alert-history row for GET /api/fleet/alerts. */
public record FleetDocumentAlertDto(
        UUID publicId,
        FleetRefType refType,
        UUID refPublicId,
        String refLabel,
        FleetDocumentCategory docType,
        String docLabel,
        LocalDate expiryDate,
        long daysLeft,
        int thresholdDays,
        LocalDateTime alertedAt
) {
}