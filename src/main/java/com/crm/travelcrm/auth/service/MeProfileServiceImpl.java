package com.crm.travelcrm.auth.service;

import com.crm.travelcrm.auth.dto.MeProfileResponse;
import com.crm.travelcrm.auth.dto.UpdateMeProfileRequest;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class MeProfileServiceImpl implements MeProfileService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public MeProfileResponse getMyProfile(User principal) {
        return toResponse(load(principal));
    }

    @Override
    @Transactional
    public MeProfileResponse updateMyProfile(User principal, UpdateMeProfileRequest request) {
        User u = load(principal);
        u.setName(request.getName().trim());
        u.setPhoneNumber(StringUtils.hasText(request.getPhoneNumber()) ? request.getPhoneNumber().trim() : null);
        userRepository.save(u);
        return toResponse(u);
    }

    /** Re-load a managed, tenant-scoped copy of the caller (the principal is a per-request snapshot). */
    private User load(User principal) {
        Long tenantId = TenantContext.getTenantId();
        return userRepository.findByIdAndTenantIdAndDeletedAtIsNull(principal.getId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private MeProfileResponse toResponse(User u) {
        return MeProfileResponse.builder()
                .name(u.getName())
                .email(u.getEmail())
                .phoneNumber(u.getPhoneNumber())
                .role(u.getRole() != null ? u.getRole().name() : null)
                .build();
    }
}