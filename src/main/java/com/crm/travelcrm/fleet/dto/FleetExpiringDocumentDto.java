package com.crm.travelcrm.fleet.dto;

import com.crm.travelcrm.fleet.enums.FleetDocumentCategory;
import com.crm.travelcrm.fleet.enums.FleetRefType;

import java.time.LocalDate;
import java.util.UUID;

/** One expiring/expired document row on the dashboard ({@code daysLeft < 0} = already expired). */
public record FleetExpiringDocumentDto(
        FleetRefType refType,
        UUID refPublicId,
        String refLabel,
        FleetDocumentCategory docType,
        String docLabel,
        LocalDate expiryDate,
        long daysLeft
) {
}