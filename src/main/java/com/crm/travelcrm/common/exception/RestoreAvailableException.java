package com.crm.travelcrm.common.exception;

import lombok.Getter;

import java.util.UUID;

/**
 * Thrown on create when the submitted phone/email collides only with a <b>soft-deleted</b>
 * (trashed) record — not an active one. Instead of a flat "already exists" error, the
 * handler turns this into a structured "restore available" 409 carrying the trashed
 * record's {@code publicId}, so the UI can offer Restore instead of forcing a duplicate.
 */
@Getter
public class RestoreAvailableException extends RuntimeException {

    /** Registry token of the trashed record (e.g. LEAD, CUSTOMER). */
    private final String entityType;

    /** publicId of the trashed record to restore. */
    private final UUID publicId;

    public RestoreAvailableException(String message, String entityType, UUID publicId) {
        super(message);
        this.entityType = entityType;
        this.publicId = publicId;
    }
}