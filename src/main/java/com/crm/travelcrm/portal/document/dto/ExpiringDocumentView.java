package com.crm.travelcrm.portal.document.dto;

import com.crm.travelcrm.portal.document.entity.TravelerDocumentType;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Projection for the expiry-reminder job — metadata only (no blob), plus the idempotency marker so
 * the job can decide which threshold to fire next.
 */
public interface ExpiringDocumentView {
    Long getId();
    UUID getPublicId();
    Long getCustomerId();
    TravelerDocumentType getType();
    LocalDate getExpiryDate();
    Integer getLastReminderDayThreshold();
}
