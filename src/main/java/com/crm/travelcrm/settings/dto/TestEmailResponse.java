package com.crm.travelcrm.settings.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Result of a test-send. {@code success=false} is a normal 200 response, not an error. */
@Data
@AllArgsConstructor
public class TestEmailResponse {
    private boolean success;
    private String  message;
    private String  error;      // SMTP error string, or null on success
    private String  testedAt;   // "Jun 25, 2026 10:20", or null on failure
}