package com.crm.travelcrm.permission.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.permission.dto.CreateTemplateRequest;
import com.crm.travelcrm.permission.dto.PermissionEntry;
import com.crm.travelcrm.permission.dto.PermissionTemplateDTO;
import com.crm.travelcrm.permission.entity.PermissionTemplate;
import com.crm.travelcrm.permission.repository.PermissionTemplateRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PermissionTemplateService {

    private static final TypeReference<Map<String, PermissionEntry>> MAP_TYPE = new TypeReference<>() {};

    private final PermissionTemplateRepository templateRepository;
    private final UserRepository userRepository;
    private final PermissionService permissionService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<PermissionTemplateDTO> list(Long tenantId) {
        return templateRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public PermissionTemplateDTO getByValue(String value, Long tenantId) {
        return toDto(requireTemplate(value, tenantId));
    }

    @Transactional
    public PermissionTemplateDTO create(CreateTemplateRequest req, Long tenantId) {
        Map<String, PermissionEntry> permissions = resolvePermissions(req, tenantId);

        PermissionTemplate template = PermissionTemplate.builder()
                .value(uniqueValue(req, tenantId))
                .label(req.getLabel().trim())
                .description(req.getDescription())
                .isDefault(Boolean.TRUE.equals(req.getIsDefault()))
                .permissionsJson(serialize(permissions))
                .build();

        return toDto(templateRepository.save(template));   // tenant_id auto-stamped
    }

    @Transactional
    public PermissionTemplateDTO update(String value, CreateTemplateRequest req, Long tenantId) {
        PermissionTemplate template = requireTemplate(value, tenantId);
        template.setLabel(req.getLabel().trim());
        template.setDescription(req.getDescription());
        if (req.getIsDefault() != null) {
            template.setDefault(req.getIsDefault());
        }
        // Replace the permission map only when the client actually sends one.
        if (req.getPermissions() != null) {
            template.setPermissionsJson(serialize(req.getPermissions()));
        }
        return toDto(templateRepository.save(template));
    }

    @Transactional
    public void delete(String value, Long tenantId) {
        templateRepository.delete(requireTemplate(value, tenantId));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Map<String, PermissionEntry> resolvePermissions(CreateTemplateRequest req, Long tenantId) {
        if (req.getPermissions() != null) {
            return req.getPermissions();
        }
        if (req.getCopyFromUserPublicId() != null) {
            User src = userRepository
                    .findByPublicIdAndTenantIdAndDeletedAtIsNull(req.getCopyFromUserPublicId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "User not found: " + req.getCopyFromUserPublicId()));
            return permissionService.rawForUserId(tenantId, src.getId());
        }
        return new HashMap<>();
    }

    private String uniqueValue(CreateTemplateRequest req, Long tenantId) {
        String base = slugify(req.getValue() != null && !req.getValue().isBlank()
                ? req.getValue() : req.getLabel());
        String value = base;
        int n = 2;
        while (templateRepository.existsByTenantIdAndValue(tenantId, value)) {
            value = base + "_" + n++;
        }
        return value;
    }

    private String slugify(String s) {
        String slug = s.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
        return slug.isBlank() ? "template" : slug;
    }

    private PermissionTemplate requireTemplate(String value, Long tenantId) {
        return templateRepository.findByTenantIdAndValue(tenantId, value)
                .orElseThrow(() -> new ResourceNotFoundException("Template not found: " + value));
    }

    private PermissionTemplateDTO toDto(PermissionTemplate t) {
        Map<String, PermissionEntry> permissions = deserialize(t.getPermissionsJson());
        int pages = (int) permissions.values().stream().filter(PermissionEntry::isAccess).count();
        return PermissionTemplateDTO.builder()
                .publicId(t.getPublicId())
                .value(t.getValue())
                .label(t.getLabel())
                .description(t.getDescription())
                .pages(pages)
                .usersCount(0)
                .isDefault(t.isDefault())
                .permissions(permissions)
                .createdAt(t.getCreatedAt())
                .build();
    }

    private Map<String, PermissionEntry> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            throw new BusinessException("Stored template is corrupt.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String serialize(Map<String, PermissionEntry> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new BusinessException("Could not serialize template.", HttpStatus.BAD_REQUEST);
        }
    }
}