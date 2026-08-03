package com.crm.travelcrm.fleet.dto;

import com.crm.travelcrm.fleet.enums.FleetAttachmentOwner;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One attached file, WITHOUT its bytes — those only ever travel through the download endpoint.
 *
 * @param deletable false once the owning money is signed (expense frozen / settlement signed):
 *                  evidence is append-only from that moment, and the UI hides its Delete action
 * @param sha256    stamped at upload; lets an auditor verify the bytes were never swapped
 */
public record FleetAttachmentDto(
        UUID publicId,
        FleetAttachmentOwner ownerType,
        String fileName,
        String contentType,
        long sizeBytes,
        String sha256,
        LocalDateTime uploadedAt,
        String uploadedBy,
        boolean deletable
) {
}
