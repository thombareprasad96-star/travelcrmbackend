package com.crm.travelcrm.fleet.integration.spi;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the job a fleet trip was run for. One of the two seams that let this module ship both as
 * a CRM feature and as an independent product.
 *
 * <p><b>Why a port and not just an import.</b> {@code FleetTripServiceImpl} imports
 * {@code booking.entity.Booking} today. That single import means a Fleet-only deployment must carry
 * the entire booking module — its tables, its permissions, its lifecycle — for one snapshot of a
 * booking code. The port removes the type, not the capability: in CRM mode the adapter resolves a
 * real {@code Booking} tenant-scoped, exactly as before.
 *
 * <p><b>Fails closed, never open.</b> When lookup is unavailable, {@link #resolve} throws rather
 * than returning empty. A caller that supplied a job publicId and silently got a trip with no link
 * is a data-loss bug the user never sees; a 400 telling them booking linking is not enabled in this
 * deployment is the honest answer.
 */
public interface FleetJobReferencePort {

    /**
     * Resolve a job by its public id within the tenant.
     *
     * @return the snapshot, or empty when the id is genuinely not found in this tenant
     * @throws com.crm.travelcrm.common.exception.BusinessException
     *         when this deployment cannot resolve job references at all
     */
    Optional<FleetJobReference> resolve(UUID publicId, Long tenantId);

    /**
     * Whether this deployment can resolve a job by public id. False in standalone mode, where a job
     * reference is free text and there is nothing to look it up in.
     */
    boolean supportsLookup();
}
