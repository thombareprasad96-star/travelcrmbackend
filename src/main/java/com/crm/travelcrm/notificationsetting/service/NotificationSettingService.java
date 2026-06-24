package com.crm.travelcrm.notificationsetting.service;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.notificationsetting.dto.NotificationStageDto;
import com.crm.travelcrm.notificationsetting.entity.NotificationSetting;
import com.crm.travelcrm.notificationsetting.repository.NotificationSettingRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationSettingService {

    private static final TypeReference<List<NotificationStageDto>> LIST_TYPE = new TypeReference<>() {};

    private final NotificationSettingRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<NotificationStageDto> get() {
        return repository.findByTenantId(currentTenantId())
                .map(s -> deserialize(s.getSettingsJson()))
                .orElseGet(ArrayList::new);
    }

    @Transactional
    public List<NotificationStageDto> save(List<NotificationStageDto> stages) {
        List<NotificationStageDto> value = stages == null ? new ArrayList<>() : stages;
        NotificationSetting row = repository.findByTenantId(currentTenantId())
                .orElseGet(() -> NotificationSetting.builder().build());
        row.setSettingsJson(serialize(value));
        repository.save(row);   // tenant_id auto-stamped on insert
        return value;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Long currentTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException("No tenant context. Authenticate with a tenant user token.",
                    HttpStatus.UNAUTHORIZED);
        }
        return tenantId;
    }

    private List<NotificationStageDto> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, LIST_TYPE);
        } catch (Exception e) {
            throw new BusinessException("Stored notification settings are corrupt.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String serialize(List<NotificationStageDto> stages) {
        try {
            return objectMapper.writeValueAsString(stages);
        } catch (Exception e) {
            throw new BusinessException("Could not serialize notification settings.",
                    HttpStatus.BAD_REQUEST);
        }
    }
}