package com.crm.travelcrm.portal.document;

import com.crm.travelcrm.portal.document.dto.TravelerDocumentDto;
import com.crm.travelcrm.portal.document.dto.TravelerDocumentView;
import com.crm.travelcrm.portal.document.entity.TravelerDocument;
import org.springframework.stereotype.Component;

/** Hand-written mapper TravelerDocument/projection → traveler-safe DTO. Never copies the bytes. */
@Component
public class PortalDocumentMapper {

    public TravelerDocumentDto toDto(TravelerDocument d) {
        return TravelerDocumentDto.builder()
                .publicId(d.getPublicId())
                .type(d.getType())
                .fileName(d.getFileName())
                .contentType(d.getContentType())
                .sizeBytes(d.getSizeBytes())
                .expiryDate(d.getExpiryDate())
                .verificationStatus(d.getVerificationStatus())
                .createdAt(d.getCreatedAt())
                .build();
    }

    public TravelerDocumentDto toDto(TravelerDocumentView v) {
        return TravelerDocumentDto.builder()
                .publicId(v.getPublicId())
                .type(v.getType())
                .fileName(v.getFileName())
                .contentType(v.getContentType())
                .sizeBytes(v.getSizeBytes())
                .expiryDate(v.getExpiryDate())
                .verificationStatus(v.getVerificationStatus())
                .createdAt(v.getCreatedAt())
                .build();
    }
}
