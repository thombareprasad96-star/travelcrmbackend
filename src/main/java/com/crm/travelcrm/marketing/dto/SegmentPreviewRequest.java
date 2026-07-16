package com.crm.travelcrm.marketing.dto;

import com.crm.travelcrm.marketing.enums.SegmentMatchType;
import jakarta.validation.Valid;

import java.util.List;

public record SegmentPreviewRequest(
        SegmentMatchType matchType,
        @Valid List<SegmentConditionDTO> conditions
) {}