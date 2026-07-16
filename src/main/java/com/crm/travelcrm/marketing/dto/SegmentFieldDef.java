package com.crm.travelcrm.marketing.dto;

import com.crm.travelcrm.marketing.enums.SegmentFieldType;

import java.util.List;

/** A segmentable customer field advertised to the query builder. */
public record SegmentFieldDef(
        String field,
        String label,
        SegmentFieldType type,
        List<String> operators,
        List<OptionDTO> options
) {}