package com.crm.travelcrm.portal.reminder;

import com.crm.travelcrm.portal.document.entity.TravelerDocumentType;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One due document-expiry reminder, handed to the delivery hook. Carries everything a sender needs
 * without exposing the document bytes.
 */
public record DocumentExpiryReminder(
        UUID documentPublicId,
        Long customerId,
        Long tenantId,
        TravelerDocumentType type,
        LocalDate expiryDate,
        long daysUntilExpiry,
        int thresholdDays
) {}
