package com.crm.travelcrm.portal.document;

import com.crm.travelcrm.common.exception.BadRequestException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.portal.document.dto.DocumentDownload;
import com.crm.travelcrm.portal.document.dto.TravelerDocumentDto;
import com.crm.travelcrm.portal.document.entity.DocumentVerificationStatus;
import com.crm.travelcrm.portal.document.entity.TravelerDocument;
import com.crm.travelcrm.portal.document.entity.TravelerDocumentType;
import com.crm.travelcrm.portal.document.repository.TravelerDocumentRepository;
import com.crm.travelcrm.portal.security.CurrentTraveler;
import com.crm.travelcrm.portal.security.TravelerPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Traveler document store. Bytes live in the DB and are only ever streamed back through the
 * ownership-checked {@link #download} — never a public URL (PII). Every method scopes by the
 * current traveler's {@code customerId}; a foreign {@code publicId} is a 404.
 */
@Service
@RequiredArgsConstructor
public class PortalDocumentService {

    /** 10 MB cap — passport/visa scans and a photo, nothing larger. */
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES =
            Set.of("image/jpeg", "image/jpg", "image/png", "application/pdf");

    private final TravelerDocumentRepository repository;
    private final PortalDocumentMapper mapper;

    @Transactional
    public TravelerDocumentDto upload(MultipartFile file, TravelerDocumentType type, LocalDate expiryDate) {
        TravelerPrincipal me = CurrentTraveler.require();
        if (type == null) throw new BadRequestException("Document type is required.");
        validate(file);

        TravelerDocument doc = TravelerDocument.builder()
                .tenantId(me.tenantId())
                .customerId(me.customerId())
                .type(type)
                .fileName(sanitize(file.getOriginalFilename()))
                .contentType(file.getContentType())
                .sizeBytes(file.getSize())
                .content(readBytes(file))
                .expiryDate(expiryDate)
                .verificationStatus(DocumentVerificationStatus.PENDING)
                .build();
        return mapper.toDto(repository.save(doc));
    }

    @Transactional(readOnly = true)
    public List<TravelerDocumentDto> myDocuments() {
        TravelerPrincipal me = CurrentTraveler.require();
        return repository.findAllByCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc(me.customerId())
                .stream().map(mapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public DocumentDownload download(UUID publicId) {
        TravelerDocument doc = requireOwned(publicId);
        return new DocumentDownload(doc.getContent(), doc.getContentType(), doc.getFileName());
    }

    @Transactional
    public void delete(UUID publicId) {
        TravelerPrincipal me = CurrentTraveler.require();
        TravelerDocument doc = requireOwned(publicId);
        doc.softDelete(me.getName());     // soft-delete — consistent with the Trash convention
        repository.save(doc);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private TravelerDocument requireOwned(UUID publicId) {
        TravelerPrincipal me = CurrentTraveler.require();
        return repository.findByPublicIdAndCustomerIdAndDeletedAtIsNull(publicId, me.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + publicId));
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BadRequestException("File is required.");
        if (file.getSize() > MAX_BYTES) throw new BadRequestException("File exceeds the 10 MB limit.");
        String ct = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!ALLOWED_TYPES.contains(ct)) {
            throw new BadRequestException("Only JPG, PNG or PDF files are allowed.");
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BadRequestException("Could not read the uploaded file.");
        }
    }

    private String sanitize(String name) {
        if (name == null || name.isBlank()) return "document";
        // Strip any path component a client might smuggle in.
        return name.replaceAll(".*[/\\\\]", "").trim();
    }
}
