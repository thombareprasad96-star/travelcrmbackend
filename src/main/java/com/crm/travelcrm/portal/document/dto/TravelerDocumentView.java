package com.crm.travelcrm.portal.document.dto;

import com.crm.travelcrm.portal.document.entity.DocumentVerificationStatus;
import com.crm.travelcrm.portal.document.entity.TravelerDocumentType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Spring Data projection for listing a traveler's documents — selects metadata columns ONLY, so the
 * {@code content} blob is never loaded for a list view. Doubles as the list DTO (traveler-safe).
 */
public interface TravelerDocumentView {
    UUID getPublicId();
    TravelerDocumentType getType();
    String getFileName();
    String getContentType();
    long getSizeBytes();
    LocalDate getExpiryDate();
    DocumentVerificationStatus getVerificationStatus();
    LocalDateTime getCreatedAt();
}
