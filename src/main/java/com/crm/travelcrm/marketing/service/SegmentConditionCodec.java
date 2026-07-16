package com.crm.travelcrm.marketing.service;

import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.marketing.dto.SegmentConditionDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Serialises a segment's condition list to/from the JSON stored in
 * {@code marketing_segments.conditions_json}. Keeps Jackson usage in one place so the
 * entity stays a plain String column and the service works with typed DTOs.
 */
@Component
@RequiredArgsConstructor
public class SegmentConditionCodec {

    private final ObjectMapper objectMapper;
    private static final TypeReference<List<SegmentConditionDTO>> TYPE = new TypeReference<>() {};

    public String toJson(List<SegmentConditionDTO> conditions) {
        try {
            return objectMapper.writeValueAsString(conditions == null ? List.of() : conditions);
        } catch (Exception e) {
            throw new BusinessException("Could not serialise segment conditions.", HttpStatus.BAD_REQUEST);
        }
    }

    public List<SegmentConditionDTO> fromJson(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, TYPE);
        } catch (Exception e) {
            // Corrupt/legacy JSON must never break a read — treat as "no conditions" (matches all).
            return List.of();
        }
    }
}