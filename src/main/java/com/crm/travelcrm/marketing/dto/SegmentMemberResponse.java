package com.crm.travelcrm.marketing.dto;

import java.util.UUID;

public record SegmentMemberResponse(
        UUID publicId,
        String name,
        String email,
        String phone,
        String city,
        String state,
        String loyaltyTier,
        String customerType,
        String status
) {}