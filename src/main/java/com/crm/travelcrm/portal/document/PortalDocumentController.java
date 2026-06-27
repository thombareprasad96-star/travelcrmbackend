package com.crm.travelcrm.portal.document;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.portal.document.dto.DocumentDownload;
import com.crm.travelcrm.portal.document.dto.TravelerDocumentDto;
import com.crm.travelcrm.portal.document.entity.TravelerDocumentType;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Traveler document upload / list / download / delete. Files are stored in the DB and streamed back
 * ONLY through {@link #download} (authenticated + ownership-checked) — there is no public URL for a
 * passport/visa scan. All actions are scoped to the caller's own customer.
 */
@RestController
@RequestMapping("/api/portal/documents")
@RequiredArgsConstructor
public class PortalDocumentController {

    private final PortalDocumentService portalDocumentService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<TravelerDocumentDto>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("type") TravelerDocumentType type,
            @RequestParam(value = "expiryDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryDate) {
        TravelerDocumentDto dto = portalDocumentService.upload(file, type, expiryDate);
        return ResponseEntity.ok(ApiResponse.success("Document uploaded", dto));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TravelerDocumentDto>>> myDocuments() {
        return ResponseEntity.ok(ApiResponse.success("Documents fetched", portalDocumentService.myDocuments()));
    }

    /** Secured retrieval — streams the bytes (no redirect to any public CDN URL). */
    @GetMapping("/{publicId}/file")
    public ResponseEntity<byte[]> download(@PathVariable UUID publicId) {
        DocumentDownload doc = portalDocumentService.download(publicId);
        MediaType mediaType = doc.contentType() != null
                ? MediaType.parseMediaType(doc.contentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + doc.fileName() + "\"")
                .body(doc.content());
    }

    @DeleteMapping("/{publicId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID publicId) {
        portalDocumentService.delete(publicId);
        return ResponseEntity.ok(ApiResponse.success("Document deleted"));
    }
}
