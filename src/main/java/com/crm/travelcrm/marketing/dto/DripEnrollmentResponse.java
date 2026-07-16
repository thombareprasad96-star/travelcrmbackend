package com.crm.travelcrm.marketing.dto;

import com.crm.travelcrm.marketing.enums.EnrollmentStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record DripEnrollmentResponse(
        UUID publicId,
        String customerName,
        EnrollmentStatus status,
        int currentStep,
        LocalDateTime nextRunAt,
        LocalDateTime enrolledAt,
        LocalDateTime completedAt
) {}