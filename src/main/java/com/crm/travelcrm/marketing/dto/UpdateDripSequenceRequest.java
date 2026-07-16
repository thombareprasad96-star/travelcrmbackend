package com.crm.travelcrm.marketing.dto;

import com.crm.travelcrm.marketing.enums.DripAudienceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record UpdateDripSequenceRequest(
        @NotBlank(message = "Sequence name is required") @Size(max = 150) String name,
        String description,
        @NotNull(message = "Audience type is required") DripAudienceType audienceType,
        UUID segmentPublicId,
        @NotEmpty(message = "Add at least one step") @Valid List<DripStepDTO> steps
) {}