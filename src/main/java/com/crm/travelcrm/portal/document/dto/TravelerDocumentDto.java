package com.crm.travelcrm.portal.document.dto;

import com.crm.travelcrm.portal.document.entity.DocumentVerificationStatus;
import com.crm.travelcrm.portal.document.entity.TravelerDocumentType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Traveler-safe document metadata (no bytes). Returned on upload and in the documents list. */
@Data
@Builder
public class TravelerDocumentDto {
    private UUID publicId;
    private TravelerDocumentType type;
    private String fileName;
    private String contentType;
    private long sizeBytes;
    private LocalDate expiryDate;
    private DocumentVerificationStatus verificationStatus;
    private LocalDateTime createdAt;
}
