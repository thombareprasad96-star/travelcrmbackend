package com.crm.travelcrm.trash.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One trashed record as exposed to a future Trash UI. Carries only the {@code publicId}
 * (never the internal Long id) plus the computed purge countdown so the client can render
 * "purges in N days" without doing date math.
 */
@Data
@Builder
public class TrashItemDto {

    /** Registry token (LEAD / CUSTOMER / BOOKING / QUOTATION) — used by restore / delete-now. */
    private String entityType;

    /** Human-friendly module name (e.g. "Leads"). */
    private String module;

    private UUID publicId;

    /** A short display label for the row (customer name, booking code, etc.). */
    private String label;

    private LocalDateTime deletedAt;
    private String deletedBy;

    /** When this record will be hard-purged (deletedAt + retention window). */
    private LocalDateTime purgeAt;

    /** Whole days remaining until purge (never negative; 0 means due on the next run). */
    private long daysUntilPurge;
}