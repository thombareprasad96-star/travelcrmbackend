package com.crm.travelcrm.common.cloudinary;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.crm.travelcrm.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class CloudinaryService {

    private final Cloudinary cloudinary;
    /** Records per-tenant storage usage (impl in the platform usage module); best-effort. */
    private final StorageMeter storageMeter;
    /** Hard-enforces the tenant's storage cap before an upload (impl in the platform usage module). */
    private final StorageQuota storageQuota;

    /**
     * What every caller of {@link #uploadImage} is actually allowed to send. Deliberately the union of
     * what the existing upload surfaces use — master/company images (incl. SVG logos and ICO favicons)
     * and PDF payment proofs — so this is a hardening, not a behaviour change. Its job is to stop the
     * endpoints being used as free file hosting for arbitrary binaries, not to police image formats.
     */
    private static final Set<String> ALLOWED_UPLOAD_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif",
            "image/svg+xml", "image/x-icon", "image/vnd.microsoft.icon", "image/ico",
            "application/pdf");

    public String uploadImage(MultipartFile file, String folder) {
        // A client-side accept="image/*" is a hint, not a control — anyone can POST here directly.
        validateUpload(file);
        // Block user-driven uploads that would exceed the tenant's plan storage (403). System-generated
        // assets go through uploadRaw(), which is intentionally NOT gated.
        storageQuota.enforceWithinQuota(file.getSize());
        try {
            Map<?, ?> result = cloudinary.uploader().upload(
                    file.getBytes(),
                    ObjectUtils.asMap(
                            "folder",        folder,
                            "resource_type", "auto"
                    )
            );
            String url = (String) result.get("secure_url");
            meterUpload(result, folder);
            return url;
        } catch (IOException e) {
            log.error("Cloudinary upload failed for folder '{}': {}", folder, e.getMessage());
            throw new RuntimeException("Image upload failed: " + e.getMessage(), e);
        }
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("File is required.");
        }
        String contentType = file.getContentType() == null
                ? ""
                : file.getContentType().toLowerCase();
        if (!ALLOWED_UPLOAD_TYPES.contains(contentType)) {
            throw new BadRequestException("Only image files (JPG, PNG, WebP, GIF, SVG, ICO) or PDF are allowed.");
        }
    }

    /**
     * Uploads raw bytes (e.g. a generated PDF) at a fixed public id, overwriting any
     * previous asset at the same id. {@code resource_type=raw} keeps the file as-is.
     *
     * @param bytes    file content
     * @param publicId full Cloudinary public id incl. folder + extension,
     *                 e.g. {@code "quotations/<uuid>.pdf"}
     * @return the secure (https) delivery URL
     */
    public String uploadRaw(byte[] bytes, String publicId) {
        try {
            Map<?, ?> result = cloudinary.uploader().upload(
                    bytes,
                    ObjectUtils.asMap(
                            "public_id",     publicId,
                            "resource_type", "raw",
                            "overwrite",     true
                    )
            );
            String url = (String) result.get("secure_url");
            // Overwrite semantics: drop any prior row for this fixed public id before re-recording,
            // so re-generating a PDF at the same id doesn't double-count.
            storageMeter.recordDelete(publicId);
            meterUpload(result, null);
            return url;
        } catch (IOException e) {
            log.error("Cloudinary raw upload failed for publicId '{}': {}", publicId, e.getMessage());
            throw new RuntimeException("File upload failed: " + e.getMessage(), e);
        }
    }

    /** Best-effort per-tenant storage metering from a Cloudinary upload result map. */
    private void meterUpload(Map<?, ?> result, String folder) {
        try {
            Object publicId = result.get("public_id");
            if (publicId == null) return;
            Object bytes = result.get("bytes");
            long sizeBytes = (bytes instanceof Number n) ? n.longValue() : 0L;
            Object rt = result.get("resource_type");
            String resourceType = rt != null ? rt.toString() : "image";
            storageMeter.recordUpload(publicId.toString(),
                    (String) result.get("secure_url"), sizeBytes, resourceType, folder);
        } catch (Exception e) {
            log.warn("Storage metering skipped for upload: {}", e.getMessage());
        }
    }

    public void deleteImage(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            return;
        }
        try {
            cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap());
            storageMeter.recordDelete(publicId);
        } catch (IOException e) {
            log.error("Cloudinary delete failed for publicId '{}': {}", publicId, e.getMessage());
            throw new RuntimeException("Image deletion failed: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts the Cloudinary public ID from a full secure URL.
     * e.g. "https://res.cloudinary.com/demo/image/upload/v123/destinations/goa.jpg"
     *   → "destinations/goa"
     */
    public String extractPublicId(String imagePath) {
        if (imagePath == null || imagePath.isBlank()) {
            return null;
        }
        int uploadIndex = imagePath.indexOf("/upload/");
        if (uploadIndex == -1) {
            return imagePath;
        }
        String afterUpload = imagePath.substring(uploadIndex + 8);
        // strip optional version segment (v1234567/)
        afterUpload = afterUpload.replaceFirst("^v\\d+/", "");
        // strip file extension
        int dotIndex = afterUpload.lastIndexOf('.');
        if (dotIndex != -1) {
            afterUpload = afterUpload.substring(0, dotIndex);
        }
        return afterUpload;
    }
}