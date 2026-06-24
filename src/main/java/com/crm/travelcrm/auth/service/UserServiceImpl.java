package com.crm.travelcrm.auth.service;

import com.crm.travelcrm.auth.dto.CreateUserRequest;
import com.crm.travelcrm.auth.dto.ResetPasswordRequest;
import com.crm.travelcrm.auth.dto.UpdateUserRequest;
import com.crm.travelcrm.auth.dto.UserDto;
import com.crm.travelcrm.auth.dto.UserResponseDTO;
import com.crm.travelcrm.auth.dto.UserStatsDTO;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.enums.Role;
import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.enums.TenantStatus;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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

    // Roles a tenant admin may assign / manage. Only the platform SUPERADMIN is excluded.
    private static final Set<Role> CREATABLE_ROLES = Set.of(
            Role.STAFF, Role.MANAGER, Role.TRAVEL_AGENT, Role.TENANT_ADMIN, Role.ACCOUNTANT);

    private final UserRepository   userRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder  passwordEncoder;

    // Authorization is enforced here at the service layer (not only in the
    // controller) so it cannot be bypassed by any other caller. USER_CREATE is
    // granted to TENANT_ADMIN only (see Role.authorities()).
    @Override
    @Transactional
    @PreAuthorize("hasAuthority('USER_CREATE')")
    public UserResponseDTO createUser(CreateUserRequest request, Long tenantId) {

        // tenantId is the caller's own tenant (from the authenticated principal,
        // passed by the controller) — never taken from the request body.
        requireActiveTenant(tenantId);

        // Whitelist: a tenant admin may mint any tenant role (Staff, Manager,
        // Travel Agent, Organization Admin, Account) — only SUPERADMIN is forbidden → 403.
        // Unparseable roles never reach here — Jackson rejects them as 400.
        if (!CREATABLE_ROLES.contains(request.getRole())) {
            throw new BusinessException(
                    "Role " + request.getRole() + " cannot be assigned by a tenant admin",
                    HttpStatus.FORBIDDEN);
        }

        // Normalize before the uniqueness check AND before persisting so the
        // check and the stored value can never diverge by case/whitespace.
        String email = request.getEmail().trim().toLowerCase();

        // Email is unique per tenant (uq_user_email_tenant) — scope the check to tenant.
        if (userRepository.existsByEmailAndTenantId(email, tenantId)) {
            throw new BusinessException(
                    "A user with email " + email + " already exists in your organization.",
                    HttpStatus.CONFLICT);
        }

        Long managerId = resolveManagerId(request.getManagerPublicId(), request.getRole(), tenantId);

        User user = User.builder()
                .name(request.getName().trim())
                .email(email)
                .password(passwordEncoder.encode(request.getPassword()))
                .role(request.getRole())
                .tenantId(tenantId)
                .managerId(managerId)
                .phoneNumber(request.getPhoneNumber())
                .isActive(true)
                .build();

        User saved = userRepository.save(user);
        log.info("User created: email={} role={} tenantId={} managerId={}",
                saved.getEmail(), saved.getRole(), tenantId, managerId);

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
    @Transactional(readOnly = true)
    public UserResponseDTO getUser(UUID publicId, Long tenantId) {
        return toResponse(findManagedUser(publicId, tenantId));
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


    @Override
    @Transactional(readOnly = true)
    public UserStatsDTO getStats(Long tenantId) {
        return UserStatsDTO.builder()
                .total(userRepository.countByTenantIdAndDeletedAtIsNull(tenantId))
                .active(userRepository.countByTenantIdAndDeletedAtIsNullAndIsActiveTrue(tenantId))
                .inactive(userRepository.countByTenantIdAndDeletedAtIsNullAndIsActiveFalse(tenantId))
                .managers(userRepository.countByTenantIdAndDeletedAtIsNullAndRole(tenantId, Role.MANAGER))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponseDTO> searchUsers(String query, Long tenantId) {
        if (query == null || query.isBlank()) {
            return getUsersByTenant(tenantId);
        }
        return userRepository.searchInTenant(tenantId, query.trim())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEmailAvailable(String email, Long tenantId) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return !userRepository.existsByEmailAndTenantId(email.trim().toLowerCase(), tenantId);
    }

    @Override
    @Transactional
    public UserResponseDTO toggleStatus(UUID publicId, Long tenantId) {
        User user = findManagedUser(publicId, tenantId);
        user.setIsActive(!Boolean.TRUE.equals(user.getIsActive()));
        User saved = userRepository.save(user);
        log.info("User status toggled: publicId={} active={} tenantId={}",
                publicId, saved.getIsActive(), tenantId);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void resetPassword(UUID publicId, ResetPasswordRequest request, Long tenantId) {
        if (request.getConfirmPassword() != null
                && !request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException("Passwords do not match.", HttpStatus.BAD_REQUEST);
        }
        User user = findManagedUser(publicId, tenantId);
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        log.info("Password reset by admin for user publicId={} tenantId={}", publicId, tenantId);
    }

    // A tenant admin whose own organization is inactive/suspended/soft-deleted
    // must not be able to provision new users. 403, not 500.
    private void requireActiveTenant(Long tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BusinessException(
                        "Your organization could not be found.", HttpStatus.FORBIDDEN));

        if (tenant.isDeleted() || tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new BusinessException(
                    "Your organization is " + tenant.getStatus()
                            + "; new users cannot be created.",
                    HttpStatus.FORBIDDEN);
        }
    }

    // Resolves the optional managerPublicId to an internal manager id.
    // Only a TRAVEL_AGENT may have a manager, and that manager must be an
    // active MANAGER inside the same tenant — never cross-tenant.
    private Long resolveManagerId(UUID managerPublicId, Role role, Long tenantId) {
        if (managerPublicId == null) {
            return null;
        }
        if (role != Role.TRAVEL_AGENT) {
            throw new BusinessException(
                    "A manager can only be assigned to a TRAVEL_AGENT.",
                    HttpStatus.BAD_REQUEST);
        }
        User manager = userRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(managerPublicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Manager not found: " + managerPublicId));
        if (manager.getRole() != Role.MANAGER) {
            throw new BusinessException(
                    "Assigned manager must have the MANAGER role.",
                    HttpStatus.BAD_REQUEST);
        }
        return manager.getId();
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