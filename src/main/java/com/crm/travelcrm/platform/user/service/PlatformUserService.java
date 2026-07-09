package com.crm.travelcrm.platform.user.service;

import com.crm.travelcrm.platform.user.dto.PlatformUserResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/** Cross-tenant user control for the SuperAdmin. */
public interface PlatformUserService {

    Page<PlatformUserResponse> listUsers(String search, UUID tenantId, Pageable pageable);

    PlatformUserResponse lock(UUID publicId);

    PlatformUserResponse unlock(UUID publicId);

    PlatformUserResponse resetPassword(UUID publicId, String newPassword);
}