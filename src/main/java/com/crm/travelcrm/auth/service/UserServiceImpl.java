package com.crm.travelcrm.auth.service;

import com.crm.travelcrm.auth.dto.CreateUserRequest;
import com.crm.travelcrm.auth.dto.UpdateUserRequest;
import com.crm.travelcrm.auth.dto.UserDto;
import com.crm.travelcrm.auth.dto.UserResponseDTO;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.enums.Role;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.EmailAlreadyExistsException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final Logger log = LogManager.getLogger(UserServiceImpl.class);

    private static final Set<Role> CREATABLE_ROLES = Set.of(Role.MANAGER, Role.AGENT);

    private final UserRepository   userRepository;
    private final PasswordEncoder  passwordEncoder;

    @Override
    @Transactional
    public UserResponseDTO createUser(CreateUserRequest request, Long tenantId) {

        if (!CREATABLE_ROLES.contains(request.getRole())) {
            throw new BusinessException(
                    "Role " + request.getRole() + " cannot be assigned by a tenant admin",
                    HttpStatus.FORBIDDEN);
        }

        if (userRepository.existsByEmailAndTenantId(request.getEmail(), tenantId)) {
            throw new EmailAlreadyExistsException(request.getEmail());
        }

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(request.getRole())
                .tenantId(tenantId)
                .phoneNumber(request.getPhoneNumber())
                .isActive(true)
                .build();

        User saved = userRepository.save(user);
        log.info("User created: email={} role={} tenantId={}", saved.getEmail(), saved.getRole(), tenantId);

        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponseDTO> getUsersByTenant(Long tenantId) {
        return userRepository.findAllByTenantIdAndDeletedAtIsNull(tenantId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public UserResponseDTO updateUser(UUID publicId, UpdateUserRequest request, Long tenantId) {

        User user = findManagedUser(publicId, tenantId);

        if (request.getRole() != null) {
            if (!CREATABLE_ROLES.contains(request.getRole())) {
                throw new BusinessException(
                        "Role " + request.getRole() + " cannot be assigned by a tenant admin",
                        HttpStatus.FORBIDDEN);
            }
            user.setRole(request.getRole());
        }
        if (request.getName() != null) {
            user.setName(request.getName());
        }
        if (request.getPhoneNumber() != null) {
            user.setPhoneNumber(request.getPhoneNumber());
        }
        if (request.getIsActive() != null) {
            user.setIsActive(request.getIsActive());
        }

        User saved = userRepository.save(user);
        log.info("User updated: publicId={} tenantId={}", publicId, tenantId);

        return toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteUser(UUID publicId, Long tenantId, String deletedBy) {

        User user = findManagedUser(publicId, tenantId);

        user.softDelete(deletedBy);
        user.setIsActive(false);   // blocks login immediately (isEnabled)
        userRepository.save(user);

        log.info("User deleted: publicId={} tenantId={} deletedBy={}", publicId, tenantId, deletedBy);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserDto> getAvailableUsers() {

        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            // Fail fast with a meaningful error instead of an NPE inside JPA.
            // Means the request was not authenticated with a tenant-scoped JWT.
            throw new BusinessException(
                    "No tenant context. Authenticate with a tenant user token.",
                    HttpStatus.UNAUTHORIZED);
        }

        List<UserDto> availableUsers = userRepository
                .findByTenantIdAndIsActiveTrueAndDeletedAtIsNullOrderByNameAsc(tenantId)
                .stream()
                .filter(user -> user.getRole() != Role.SUPERADMIN)
                .map(user -> new UserDto(
                        user.getPublicId(),
                        user.getName(),
                        user.getRole().name(),
                        user.getEmail()
                ))
                .toList();

        log.info("Retrieved {} available users for tenant: {}",
                availableUsers.size(), tenantId);

        return availableUsers;
    }


    // Loads a tenant user that a tenant admin is allowed to manage.
    // Restricting to CREATABLE_ROLES also prevents admins from editing/deleting
    // other tenant admins — including themselves.
    private User findManagedUser(UUID publicId, Long tenantId) {
        User user = userRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + publicId));

        if (!CREATABLE_ROLES.contains(user.getRole())) {
            throw new BusinessException(
                    "Users with role " + user.getRole() + " cannot be managed by a tenant admin",
                    HttpStatus.FORBIDDEN);
        }
        return user;
    }

    private UserResponseDTO toResponse(User user) {
        return UserResponseDTO.builder()
                .publicId(user.getPublicId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole())
                .phoneNumber(user.getPhoneNumber())
                .isActive(user.getIsActive())
                .createdAt(user.getCreatedAt())
                .build();
    }
}