package com.crm.travelcrm.marketing.dto;

import java.util.UUID;

/** A light customer preview row returned by the segment preview. */
public record SegmentSampleDTO(
        UUID publicId,
        String name,
        String email,
        String phone,
        String city,
        String loyaltyTier
) {}