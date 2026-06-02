package com.crm.travelcrm.auth.entity;

import com.crm.travelcrm.auth.enums.Role;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Full Name
    @Column(nullable = false)
    private String name;

    // Email
    @Column(unique = true, nullable = false)
    private String email;

    // Indian Mobile Number
    @Column(unique = true, nullable = false, length = 10)
    private String phoneNumber;

    // Encrypted Password
    @Column(nullable = false)
    private String password;

    // USER ROLE
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    // Account Status
    @Builder.Default
    private boolean active = true;

    // Forgot Password Reset Token
    private String resetToken;

    // Reset Token Expiry
    private LocalDateTime resetTokenExpiry;

    // Mobile OTP
    private String otp;

    // OTP Expiry
    private LocalDateTime otpExpiry;

    // Created Date
    @Builder.Default
    private LocalDateTime createdAt =
            LocalDateTime.now();
}