package com.crm.travelcrm.marketing.dto;

import com.crm.travelcrm.marketing.enums.DripAudienceType;
import com.crm.travelcrm.marketing.enums.DripStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record DripSequenceResponse(
        UUID publicId,
        String name,
        String description,
        DripStatus status,
        DripAudienceType audienceType,
        UUID segmentPublicId,
        String segmentName,
        List<DripStepDTO> steps,
        long enrolledCount,
        long activeCount,
        long completedCount,
        String ownerName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}