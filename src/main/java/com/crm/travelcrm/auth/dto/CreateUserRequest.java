package com.crm.travelcrm.auth.dto;

import com.crm.travelcrm.auth.enums.Role;
import com.crm.travelcrm.auth.util.UsernamePolicy;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateUserRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 150, message = "Name must be 150 characters or fewer")
    private String name;

    // The login identifier. Normalized (trimmed + lowercased) and checked for uniqueness in
    // UserServiceImpl; the pattern deliberately accepts uppercase so "Prasad" is folded rather than
    // rejected. Uniqueness is platform-wide, matching uq_users_username_active.
    @NotBlank(message = "Username is required")
    @Size(min = UsernamePolicy.MIN_LENGTH, max = UsernamePolicy.MAX_LENGTH,
          message = "Username must be 3–80 characters")
    @Pattern(regexp = UsernamePolicy.PATTERN, message = UsernamePolicy.PATTERN_MESSAGE)
    private String username;

    // Contact address, not a credential. NOT checked for uniqueness on purpose — a whole agency may
    // share one organization mailbox.
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 150, message = "Email must be 150 characters or fewer")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 100, message = "Password must be 8–100 characters")
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).*$",
        message = "Password must contain at least one uppercase letter, one lowercase letter, and one digit"
    )
    private String password;

    @NotNull(message = "Role is required")
    private Role role;

    @Size(max = 20, message = "Phone number must be 20 characters or fewer")
    private String phoneNumber;

    // Optional. Only meaningful for TRAVEL_AGENT — the publicId of the MANAGER
    // who owns this agent. Resolved + validated server-side (same tenant, role).
    private UUID managerPublicId;
}