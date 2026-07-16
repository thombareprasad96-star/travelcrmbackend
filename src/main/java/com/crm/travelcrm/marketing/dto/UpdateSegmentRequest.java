package com.crm.travelcrm.marketing.dto;

import com.crm.travelcrm.marketing.enums.SegmentMatchType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateSegmentRequest(
        @NotBlank(message = "Segment name is required") @Size(max = 150) String name,
        @Size(max = 500) String description,
        @NotNull(message = "Match type is required") SegmentMatchType matchType,
        @Valid List<SegmentConditionDTO> conditions
) {}