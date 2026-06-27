package com.crm.travelcrm.ai.tool;

import java.util.UUID;

/** Tiny null-safe formatting + parsing helpers shared by the Disha tools. */
final class ToolFmt {

    private ToolFmt() {}

    static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** Parse a publicId the model supplied; a clear error (not a 500) when it isn't a UUID. */
    static UUID uuid(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            throw new IllegalArgumentException("A publicId (UUID) is required.");
        }
        try {
            return UUID.fromString(publicId.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Not a valid publicId (UUID): " + publicId);
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