package com.crm.travelcrm.auth.service;

import com.crm.travelcrm.auth.dto.CreateUserRequest;
import com.crm.travelcrm.auth.dto.ResetPasswordRequest;
import com.crm.travelcrm.auth.dto.UpdateUserRequest;
import com.crm.travelcrm.auth.dto.UserDto;
import com.crm.travelcrm.auth.dto.UserResponseDTO;
import com.crm.travelcrm.auth.dto.UserStatsDTO;

import java.util.List;
import java.util.UUID;

public interface UserService {
    UserResponseDTO createUser(CreateUserRequest request, Long tenantId);
    List<UserResponseDTO> getUsersByTenant(Long tenantId);
    UserResponseDTO getUser(UUID publicId, Long tenantId);
    UserResponseDTO updateUser(UUID publicId, UpdateUserRequest request, Long tenantId);
    void deleteUser(UUID publicId, Long tenantId, String deletedBy);
    List<UserDto> getAvailableUsers();

    UserStatsDTO getStats(Long tenantId);
    List<UserResponseDTO> searchUsers(String query, Long tenantId);
    boolean isEmailAvailable(String email, Long tenantId);
    UserResponseDTO toggleStatus(UUID publicId, Long tenantId);
    void resetPassword(UUID publicId, ResetPasswordRequest request, Long tenantId);
}