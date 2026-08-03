package com.crm.travelcrm.fleet.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.fleet.dto.FleetAttachmentDto;
import com.crm.travelcrm.fleet.enums.FleetAttachmentOwner;
import com.crm.travelcrm.fleet.service.FleetAttachmentService;
import com.crm.travelcrm.fleet.service.FleetAttachmentService.FleetAttachmentDownload;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Receipt images, certificate scans, signed settlement sheets — Postgres {@code bytea}, served
 * only through here. Never Cloudinary: these are financial and identity papers, and Cloudinary
 * URLs are public and unauthenticated.
 *
 * <p>Class guard is FLEET_READ; the service ADDITIONALLY requires FLEET_MONEY_READ whenever the
 * owner is an expense or a settlement — money evidence rides the money grant. A compliance scan
 * is operational and stays at FLEET_READ.
 */
@RestController
@RequestMapping("/api/fleet/attachments")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('FLEET_READ')")
public class FleetAttachmentController {

    private final FleetAttachmentService attachmentService;

    /** Multipart: {@code file} + {@code ownerType} + {@code ownerId} (the owner's publicId). */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('FLEET_CREATE','FLEET_UPDATE')")
    public ResponseEntity<ApiResponse<FleetAttachmentDto>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("ownerType") FleetAttachmentOwner ownerType,
            @RequestParam("ownerId") UUID ownerId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("File attached",
                        attachmentService.upload(ownerType, ownerId, file), 201));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<FleetAttachmentDto>>> list(
            @RequestParam("ownerType") FleetAttachmentOwner ownerType,
            @RequestParam("ownerId") UUID ownerId) {
        return ResponseEntity.ok(ApiResponse.success("Attachments fetched",
                attachmentService.list(ownerType, ownerId)));
    }

    /** The bytes. Inline disposition so an image or PDF opens in the browser tab. */
    @GetMapping("/{publicId}/file")
    public ResponseEntity<byte[]> file(@PathVariable UUID publicId) {
        FleetAttachmentDownload d = attachmentService.download(publicId);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(d.contentType());
        } catch (RuntimeException e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(d.fileName() == null ? "file" : d.fileName())
                                .build().toString())
                .body(d.content());
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAuthority('FLEET_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID publicId) {
        attachmentService.delete(publicId);
        return ResponseEntity.ok(ApiResponse.success("Attachment removed"));
    }
}
