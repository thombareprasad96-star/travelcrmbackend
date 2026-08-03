package com.crm.travelcrm.fleet.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A party in the fleet's own directory.
 *
 * @param vehicleCount how many vehicles are currently attributed to this party — the first thing an
 *                     owner looks at, and what makes the delete guard's refusal understandable
 */
public record FleetPartyResponseDto(
        UUID publicId,
        String name,
        String contactPerson,
        String phone,
        String email,
        String address,
        String city,
        String gstin,
        String pan,
        String bankName,
        String accountName,
        String accountNumber,
        String ifscCode,
        String upiId,
        BigDecimal agreedRate,
        String notes,
        boolean active,
        long vehicleCount,
        LocalDateTime createdAt
) {
}
