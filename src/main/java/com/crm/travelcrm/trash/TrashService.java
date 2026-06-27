package com.crm.travelcrm.trash;

import com.crm.travelcrm.trash.dto.TrashGroupDto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Centralized Trash lifecycle for every {@link TrashableType}. One mechanism drives
 * listing, restore, delete-now and the scheduled purge across all modules.
 */
public interface TrashService {

    /** All trashed records for the current tenant, grouped by module, with purge countdown. */
    List<TrashGroupDto> listTrash();

    /** Restore a trashed record (clears deletedAt/deletedBy). Cascades where documented. */
    void restore(String entityTypeKey, UUID publicId);

    /** Hard-delete a single trashed record now (most-restricted permission). */
    void deleteNow(String entityTypeKey, UUID publicId);

    /**
     * Hard-delete every record of every type whose {@code deletedAt < cutoff} for the
     * tenant currently set in {@code TenantContext}. Called once per tenant by the purge
     * scheduler. Idempotent — re-running finds nothing already removed.
     */
    void purgeForCurrentTenant(LocalDateTime cutoff);
}