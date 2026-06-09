package com.crm.travelcrm.common.exception;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class ErrorResponse {
    private int status;
    private String error;      // e.g. "NOT_FOUND", "VALIDATION_ERROR"
    private String message;
    private LocalDateTime timestamp;
}