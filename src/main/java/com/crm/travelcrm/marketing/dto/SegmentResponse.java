package com.crm.travelcrm.marketing.dto;

import com.crm.travelcrm.marketing.enums.SegmentMatchType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record SegmentResponse(
        UUID publicId,
        String name,
        String description,
        SegmentMatchType matchType,
        List<SegmentConditionDTO> conditions,
        long memberCount,
        String ownerName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}