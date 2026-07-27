package com.crm.travelcrm.auth.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class LoginResponseDTO {
    private String name;
    private String message;
    private String token;
    private String tokenType;
    private UUID   id;
    private String email;
    private String role;
    private boolean mfaRequired;
    private boolean mfaSetupRequired;
    private String mfaChallengeId;
    private Long mfaExpiresInSeconds;
    private String mfaIssuer;
    private String mfaManualEntryKey;
    private String mfaOtpAuthUri;
    private boolean mustChangePassword;
    private boolean setupRequired;

    public LoginResponseDTO(String name, String message, String token, String tokenType,
                            UUID id, String email, String role) {
        this.name = name;
        this.message = message;
        this.token = token;
        this.tokenType = tokenType;
        this.id = id;
        this.email = email;
        this.role = role;
        this.mfaRequired = false;
    }

    public static LoginResponseDTO mfaRequired(String name, String email, UUID id,
                                               String challengeId, long expiresInSeconds) {
        LoginResponseDTO dto = new LoginResponseDTO(
                name,
                "MFA verification required",
                null,
                null,
                id,
                email,
                "SUPER_ADMIN");
        dto.mfaRequired = true;
        dto.mfaChallengeId = challengeId;
        dto.mfaExpiresInSeconds = expiresInSeconds;
        return dto;
    }

    public static LoginResponseDTO mfaSetupRequired(String name, String email, UUID id,
                                                    String challengeId, long expiresInSeconds,
                                                    String issuer, String manualEntryKey,
                                                    String otpAuthUri) {
        LoginResponseDTO dto = mfaRequired(name, email, id, challengeId, expiresInSeconds);
        dto.message = "MFA setup required";
        dto.mfaSetupRequired = true;
        dto.mfaIssuer = issuer;
        dto.mfaManualEntryKey = manualEntryKey;
        dto.mfaOtpAuthUri = otpAuthUri;
        return dto;
    }
}
