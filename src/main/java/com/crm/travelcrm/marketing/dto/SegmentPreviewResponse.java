package com.crm.travelcrm.marketing.dto;

import java.util.List;

public record SegmentPreviewResponse(
        long totalCount,
        List<SegmentSampleDTO> sample
) {}