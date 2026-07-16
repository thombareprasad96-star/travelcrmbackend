package com.crm.travelcrm.marketing.dto;

import com.crm.travelcrm.marketing.enums.ConditionOperator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * One rule in a segment: a customer field, an operator, and a value whose shape depends
 * on the operator (String for EQUALS/CONTAINS, List for IN/NOT_IN, Number for MONTH,
 * "yyyy-MM-dd" for DATE, [start,end] for BETWEEN, absent for IS_SET/IS_NOT_SET).
 */
public record SegmentConditionDTO(
        @NotBlank(message = "Condition field is required") String field,
        @NotNull(message = "Condition operator is required") ConditionOperator operator,
        Object value
) {}