package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.common.cloudinary.StorageQuota;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.fleet.dto.FleetAttachmentDto;
import com.crm.travelcrm.fleet.entity.FleetAttachment;
import com.crm.travelcrm.fleet.entity.FleetComplianceDocument;
import com.crm.travelcrm.fleet.entity.FleetExpense;
import com.crm.travelcrm.fleet.entity.FleetTripSettlement;
import com.crm.travelcrm.fleet.enums.FleetAttachmentOwner;
import com.crm.travelcrm.fleet.repository.FleetAttachmentRepository;
import com.crm.travelcrm.fleet.repository.FleetAttachmentRepository.FleetAttachmentView;
import com.crm.travelcrm.fleet.repository.FleetComplianceDocumentRepository;
import com.crm.travelcrm.fleet.repository.FleetExpenseRepository;
import com.crm.travelcrm.fleet.repository.FleetTripSettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Fleet attachments — receipt images, certificate scans, signed settlement sheets.
 *
 * <p>Three rules carry the design (see {@code docs/FLEET_MODULE_REDESIGN.md} §4.6):
 *
 * <ul>
 *   <li><b>Bytes live in Postgres</b> ({@code bytea}), served only through the authenticated,
 *       tenant-scoped download — never a public CDN URL. {@code TravelerDocument} precedent.</li>
 *   <li><b>Quota-visible.</b> The bytes count toward {@code Tenant.maxStorageMb} through the same
 *       three consumers as every other stored file (gate, dashboard, alerts). Expense and
 *       settlement uploads hard-block at the limit; a COMPLIANCE scan is never refused at the
 *       plain limit — an insurance certificate that must be on file beats a full disk — and only
 *       stops at gross overage (110%).</li>
 *   <li><b>Append-only once signed.</b> Upload is always allowed; DELETE is refused the moment the
 *       owning expense stops being editable or the settlement is signed. Removing evidence from a
 *       signed sheet is the fraud vector; adding a late-found receipt is normal life. The SHA-256
 *       stamped at upload makes byte-swapping detectable afterwards.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FleetAttachmentServiceImpl implements FleetAttachmentService {

    /** 10 MB — same cap as traveler documents: a phone photo or a scan, nothing larger. */
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp", "application/pdf");
    /** Compliance uploads stop only at gross overage, not at the plain limit. */
    private static final double COMPLIANCE_GRACE = 1.10;

    private final FleetAttachmentRepository attachmentRepository;
    private final FleetExpenseRepository expenseRepository;
    private final FleetComplianceDocumentRepository documentRepository;
    private final FleetTripSettlementRepository settlementRepository;
    /** THE freeze definition — shared, so evidence deletability never disagrees with row editability. */
    private final FleetExpenseService expenseService;
    private final StorageQuota storageQuota;

    // ── Commands ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public FleetAttachmentDto upload(FleetAttachmentOwner ownerType, UUID ownerPublicId, MultipartFile file) {
        if (ownerType == null) {
            throw new BusinessException("Say what this file is attached to", HttpStatus.BAD_REQUEST);
        }
        Long tenantId = FleetContext.tenantId();
        validate(file);

        // Authorise BEFORE resolving: otherwise the 404-vs-403 difference tells a user without the
        // money grant whether a given expense publicId exists. Same ordering as list().
        assertMoneyReadable(ownerType);
        Owner owner = resolveOwner(ownerType, ownerPublicId, tenantId);

        if (ownerType == FleetAttachmentOwner.DOCUMENT) {
            storageQuota.enforceWithinQuota(tenantId, file.getSize(), COMPLIANCE_GRACE);
        } else {
            storageQuota.enforceWithinQuota(tenantId, file.getSize());
        }

        byte[] bytes = readBytes(file);
        FleetAttachment attachment = FleetAttachment.builder()
                .tenantId(tenantId)
                .ownerType(ownerType)
                .expenseId(ownerType == FleetAttachmentOwner.EXPENSE ? owner.id() : null)
                .documentId(ownerType == FleetAttachmentOwner.DOCUMENT ? owner.id() : null)
                .settlementId(ownerType == FleetAttachmentOwner.SETTLEMENT ? owner.id() : null)
                .fileName(sanitize(file.getOriginalFilename()))
                .contentType(file.getContentType())
                .sizeBytes(bytes.length)
                .sha256(sha256Hex(bytes))
                .content(bytes)
                .build();

        FleetAttachment saved = attachmentRepository.save(attachment);
        log.info("Fleet attachment uploaded | id: {} | owner: {} {} | {} bytes | tenantId: {}",
                saved.getId(), ownerType, owner.id(), bytes.length, tenantId);
        return toDto(saved, owner.deletable());
    }

    @Override
    @Transactional
    public void delete(UUID publicId) {
        Long tenantId = FleetContext.tenantId();
        FleetAttachment attachment = findOrThrow(publicId, tenantId);
        assertMoneyReadable(attachment.getOwnerType());

        if (!ownerStillMutable(attachment, tenantId)) {
            throw new BusinessException(
                    "This file is evidence on signed money — it can no longer be removed. "
                            + "If it is wrong, attach the correct one beside it",
                    HttpStatus.CONFLICT);
        }
        attachment.softDelete(FleetContext.username());
        attachmentRepository.save(attachment);
        log.info("Fleet attachment soft-deleted | id: {} | tenantId: {}", attachment.getId(), tenantId);
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<FleetAttachmentDto> list(FleetAttachmentOwner ownerType, UUID ownerPublicId) {
        Long tenantId = FleetContext.tenantId();
        assertMoneyReadable(ownerType);
        Owner owner = resolveOwner(ownerType, ownerPublicId, tenantId);

        List<FleetAttachmentView> rows = switch (ownerType) {
            case EXPENSE -> attachmentRepository
                    .findAllByTenantIdAndExpenseIdAndDeletedAtIsNullOrderByCreatedAtDesc(tenantId, owner.id());
            case DOCUMENT -> attachmentRepository
                    .findAllByTenantIdAndDocumentIdAndDeletedAtIsNullOrderByCreatedAtDesc(tenantId, owner.id());
            case SETTLEMENT -> attachmentRepository
                    .findAllByTenantIdAndSettlementIdAndDeletedAtIsNullOrderByCreatedAtDesc(tenantId, owner.id());
        };
        return rows.stream()
                .map(v -> new FleetAttachmentDto(
                        v.getPublicId(), ownerType, v.getFileName(), v.getContentType(),
                        v.getSizeBytes(), v.getSha256(), v.getCreatedAt(), v.getCreatedBy(),
                        owner.deletable()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public FleetAttachmentDownload download(UUID publicId) {
        Long tenantId = FleetContext.tenantId();
        FleetAttachment attachment = findOrThrow(publicId, tenantId);
        assertMoneyReadable(attachment.getOwnerType());
        return new FleetAttachmentDownload(
                attachment.getContent(), attachment.getContentType(), attachment.getFileName());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Internal id + whether evidence on this owner may still be deleted. */
    private record Owner(Long id, boolean deletable) {
    }

    /** Resolves in-tenant or 404s — a foreign publicId returns "not found", never data. */
    private Owner resolveOwner(FleetAttachmentOwner ownerType, UUID ownerPublicId, Long tenantId) {
        if (ownerPublicId == null) {
            throw new BusinessException("Say which record this file belongs to", HttpStatus.BAD_REQUEST);
        }
        return switch (ownerType) {
            case EXPENSE -> {
                FleetExpense e = expenseRepository
                        .findByPublicIdAndTenantIdAndDeletedAtIsNull(ownerPublicId, tenantId)
                        .orElseThrow(() -> new ResourceNotFoundException("Expense not found: " + ownerPublicId));
                yield new Owner(e.getId(), expenseService.isEditable(e));
            }
            case DOCUMENT -> {
                FleetComplianceDocument d = documentRepository
                        .findByPublicIdAndTenantIdAndDeletedAtIsNull(ownerPublicId, tenantId)
                        .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + ownerPublicId));
                yield new Owner(d.getId(), true);
            }
            case SETTLEMENT -> {
                FleetTripSettlement s = settlementRepository
                        .findByPublicIdAndTenantIdAndDeletedAtIsNull(ownerPublicId, tenantId)
                        .orElseThrow(() -> new ResourceNotFoundException("Settlement not found: " + ownerPublicId));
                yield new Owner(s.getId(), s.getStatus().isMutable());
            }
        };
    }

    /** Re-derives deletability for an EXISTING attachment (delete path). */
    private boolean ownerStillMutable(FleetAttachment attachment, Long tenantId) {
        return switch (attachment.getOwnerType()) {
            case EXPENSE -> expenseRepository.findByIdAndTenantId(attachment.getExpenseId(), tenantId)
                    .map(expenseService::isEditable)
                    .orElse(false);
            case DOCUMENT -> true;
            case SETTLEMENT -> settlementRepository.findByIdAndTenantId(attachment.getSettlementId(), tenantId)
                    .map(s -> s.getStatus().isMutable())
                    .orElse(false);
        };
    }

    /**
     * Receipt and settlement files are money evidence, so seeing or touching them rides the same
     * grant as the money itself. A compliance scan is operational and rides plain FLEET_READ,
     * which the controller already enforces.
     */
    private void assertMoneyReadable(FleetAttachmentOwner ownerType) {
        if (ownerType == FleetAttachmentOwner.DOCUMENT) return;
        if (!FleetContext.canSeeMoney()) {
            throw new BusinessException(
                    "You don't have access to fleet money records", HttpStatus.FORBIDDEN);
        }
    }

    private FleetAttachment findOrThrow(UUID publicId, Long tenantId) {
        return attachmentRepository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment not found: " + publicId));
    }

    private FleetAttachmentDto toDto(FleetAttachment a, boolean deletable) {
        return new FleetAttachmentDto(
                a.getPublicId(), a.getOwnerType(), a.getFileName(), a.getContentType(),
                a.getSizeBytes(), a.getSha256(), a.getCreatedAt(), a.getCreatedBy(), deletable);
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("File is required", HttpStatus.BAD_REQUEST);
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException("File exceeds the 10 MB limit", HttpStatus.BAD_REQUEST);
        }
        String ct = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!ALLOWED_TYPES.contains(ct)) {
            throw new BusinessException("Only JPG, PNG, WEBP or PDF files are allowed", HttpStatus.BAD_REQUEST);
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("Could not read the uploaded file", HttpStatus.BAD_REQUEST);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every JVM; this is unreachable.
            throw new IllegalStateException(e);
        }
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) return "file";
        String cleaned = name.replaceAll("[\\r\\n\"\\\\/]", "_");
        return cleaned.length() > 255 ? cleaned.substring(cleaned.length() - 255) : cleaned;
    }
}
