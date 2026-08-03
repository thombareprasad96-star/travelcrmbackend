package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.fleet.dto.FleetAttachmentDto;
import com.crm.travelcrm.fleet.enums.FleetAttachmentOwner;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface FleetAttachmentService {

    /**
     * Attach a file to an expense, compliance document or settlement. Upload is ALWAYS allowed —
     * a late receipt after the sheet is signed is normal life, and more evidence never hurts an
     * audit. What is refused later is deletion.
     */
    FleetAttachmentDto upload(FleetAttachmentOwner ownerType, UUID ownerPublicId, MultipartFile file);

    /** Attachments of one owner, newest first — metadata only, the bytes never ride along. */
    List<FleetAttachmentDto> list(FleetAttachmentOwner ownerType, UUID ownerPublicId);

    /** The bytes, for the authenticated download endpoint. */
    FleetAttachmentDownload download(UUID publicId);

    /**
     * Refused once the owning money is signed (expense frozen / settlement no longer mutable):
     * evidence is append-only from that moment.
     */
    void delete(UUID publicId);

    /** Bytes + the headers the controller needs. */
    record FleetAttachmentDownload(byte[] content, String contentType, String fileName) {
    }
}
