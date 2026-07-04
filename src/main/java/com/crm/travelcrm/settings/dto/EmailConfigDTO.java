package com.crm.travelcrm.settings.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Email/SMTP config sent to the UI. The password is NEVER returned — only {@code passwordSet}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailConfigDTO {
    private boolean configured;
    private String  smtpHost;
    private String  smtpPort;       // formatted, e.g. "587 (TLS)"
    private int     portNumber;     // raw, e.g. 587
    private String  encryption;     // TLS | SSL | None
    private String  username;
    private boolean passwordSet;    // true = a password is stored (value never returned)
    private String  fromEmail;
    private String  fromName;
    private String  lastTestedAt;   // "Jun 25, 2026" or null
    private String  lastSavedAt;    // "Jun 25, 2026" or null
}