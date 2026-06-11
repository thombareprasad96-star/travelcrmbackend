package com.crm.travelcrm.auth.service;

import com.crm.travelcrm.auth.dto.CreateUserRequest;
import com.crm.travelcrm.auth.dto.UpdateUserRequest;
import com.crm.travelcrm.auth.dto.UserDto;
import com.crm.travelcrm.auth.dto.UserResponseDTO;

import java.util.List;
import java.util.UUID;

public interface UserService {
    UserResponseDTO createUser(CreateUserRequest request, Long tenantId);
    List<UserResponseDTO> getUsersByTenant(Long tenantId);
    UserResponseDTO updateUser(UUID publicId, UpdateUserRequest request, Long tenantId);
    void deleteUser(UUID publicId, Long tenantId, String deletedBy);
    List<UserDto> getAvailableUsers();
}