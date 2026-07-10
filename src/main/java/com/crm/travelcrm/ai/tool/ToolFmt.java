package com.crm.travelcrm.ai.tool;

import com.crm.travelcrm.common.exception.BadRequestException;

import java.util.UUID;

/** Tiny null-safe formatting + parsing helpers shared by the Disha tools. */
final class ToolFmt {

    private ToolFmt() {}

    static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /**
     * Parse a publicId the model supplied; a clear error (not a 500) when it isn't a UUID.
     *
     * <p>Throws {@link BadRequestException} rather than {@code IllegalArgumentException} because the
     * message here is written for a reader. {@code IllegalArgumentException} is also thrown by the JDK
     * and Hibernate, so its handler can no longer echo the message — see {@code GlobalExceptionHandler}.
     */
    static UUID uuid(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            throw new BadRequestException("A publicId (UUID) is required.");
        }
        try {
            return UUID.fromString(publicId.trim());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Not a valid publicId (UUID): " + publicId);
        }
    }

    static int pageOrDefault(Integer page) {
        return (page == null || page < 0) ? 0 : page;
    }

    static int sizeOrDefault(Integer size) {
        if (size == null || size < 1) return 20;
        return Math.min(size, 50);
    }
}