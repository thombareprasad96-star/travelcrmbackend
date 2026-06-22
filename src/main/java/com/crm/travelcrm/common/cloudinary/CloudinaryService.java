package com.crm.travelcrm.common.cloudinary;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CloudinaryService {

    private final Cloudinary cloudinary;

    public String uploadImage(MultipartFile file, String folder) {
        try {
            Map<?, ?> result = cloudinary.uploader().upload(
                    file.getBytes(),
                    ObjectUtils.asMap(
                            "folder",        folder,
                            "resource_type", "auto"
                    )
            );
            return (String) result.get("secure_url");
        } catch (IOException e) {
            log.error("Cloudinary upload failed for folder '{}': {}", folder, e.getMessage());
            throw new RuntimeException("Image upload failed: " + e.getMessage(), e);
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
            return (String) result.get("secure_url");
        } catch (IOException e) {
            log.error("Cloudinary raw upload failed for publicId '{}': {}", publicId, e.getMessage());
            throw new RuntimeException("File upload failed: " + e.getMessage(), e);
        }
    }

    public void deleteImage(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            return;
        }
        try {
            cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap());
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
    public String extractPublicId(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }
        int uploadIndex = imageUrl.indexOf("/upload/");
        if (uploadIndex == -1) {
            return imageUrl;
        }
        String afterUpload = imageUrl.substring(uploadIndex + 8);
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