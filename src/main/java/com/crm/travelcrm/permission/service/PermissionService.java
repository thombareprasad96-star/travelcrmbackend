package com.crm.travelcrm.permission.service;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.enums.Role;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.permission.dto.PermissionEntry;
import com.crm.travelcrm.permission.dto.UserPermissionsDTO;
import com.crm.travelcrm.permission.entity.UserPermission;
import com.crm.travelcrm.permission.enums.Permission;
import com.crm.travelcrm.permission.repository.UserPermissionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PermissionService {

    private static final TypeReference<Map<String, PermissionEntry>> MAP_TYPE = new TypeReference<>() {};

    private final UserPermissionRepository userPermissionRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public UserPermissionsDTO getForUser(UUID userPublicId, Long tenantId) {
        User target = resolveUser(userPublicId, tenantId);
        Map<String, PermissionEntry> permissions = userPermissionRepository
                .findByTenantIdAndUserId(tenantId, target.getId())
                .map(up -> deserialize(up.getPermissionsJson()))
                .orElseGet(() -> defaultsAsMap(target.getRole()));
        return toDto(userPublicId, permissions);
    }

    // Role default permissions as an editable map — shown when a user has no saved
    // customization yet, so the admin edits from the real starting point (not blank).
    private Map<String, PermissionEntry> defaultsAsMap(Role role) {
        Map<String, PermissionEntry> map = new HashMap<>();
        for (Permission p : Permission.defaultsFor(role)) {
            map.put(p.name(), new PermissionEntry(true, "own"));
        }
        return map;
    }

    @Transactional
    public UserPermissionsDTO save(UUID userPublicId, Map<String, PermissionEntry> permissions, Long tenantId) {
        User target = resolveUser(userPublicId, tenantId);
        UserPermission row = userPermissionRepository
                .findByTenantIdAndUserId(tenantId, target.getId())
                .orElseGet(() -> UserPermission.builder().userId(target.getId()).build());
        Map<String, PermissionEntry> map = permissions == null ? new HashMap<>() : permissions;
        row.setPermissionsJson(serialize(map));
        userPermissionRepository.save(row);   // tenant_id auto-stamped on insert
        return toDto(userPublicId, map);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    // Resolve a managed user's permissions map for "copy from user" in templates.
    Map<String, PermissionEntry> rawForUserId(Long tenantId, Long userId) {
        return userPermissionRepository.findByTenantIdAndUserId(tenantId, userId)
                .map(up -> deserialize(up.getPermissionsJson()))
                .orElseGet(HashMap::new);
    }

    /**
     * The user's saved permission map, or {@code null} when the user has NO persisted row.
     * Unlike {@link #rawForUserId}, this distinguishes "never customized" (null → caller falls
     * back to role defaults) from "explicitly saved with everything off" (empty map → no grants).
     * Used by {@link EffectivePermissionResolver} so turning every permission off actually means
     * "no access" instead of silently resetting to the role default set.
     */
    Map<String, PermissionEntry> savedMapOrNull(Long tenantId, Long userId) {
        return userPermissionRepository.findByTenantIdAndUserId(tenantId, userId)
                .map(up -> deserialize(up.getPermissionsJson()))
                .orElse(null);
    }

    private User resolveUser(UUID publicId, Long tenantId) {
        return userRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + publicId));
    }

    private UserPermissionsDTO toDto(UUID userPublicId, Map<String, PermissionEntry> map) {
        int pages = (int) map.values().stream().filter(PermissionEntry::isAccess).count();
        return UserPermissionsDTO.builder()
                .userPublicId(userPublicId)
                .pages(pages)
                .total(map.size())
                .permissions(map)
                .build();
    }

    private Map<String, PermissionEntry> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            throw new BusinessException("Stored permissions are corrupt.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String serialize(Map<String, PermissionEntry> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new BusinessException("Could not serialize permissions.", HttpStatus.BAD_REQUEST);
        }
    }
}