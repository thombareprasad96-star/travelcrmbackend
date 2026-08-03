package com.crm.travelcrm.fleet.integration.spi;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the owner/counterparty of a vehicle that the agency does not own outright. The second of
 * the two seams between fleet and the CRM.
 *
 * <p>Replaces the direct {@code vendor.entity.Vendor} import in {@code FleetVehicleServiceImpl}. In
 * CRM mode the adapter reads the real Vendor master, tenant-scoped, exactly as today. In standalone
 * mode the directory is fleet-owned.
 *
 * <p>Same fail-closed rule as {@link FleetJobReferencePort}: a supplied party id that cannot be
 * resolved is an error, never a silently dropped link.
 */
public interface FleetPartyPort {

    /**
     * @return the snapshot, or empty when the id is genuinely not found in this tenant
     * @throws com.crm.travelcrm.common.exception.BusinessException
     *         when this deployment has no party directory at all
     */
    Optional<FleetParty> resolve(UUID publicId, Long tenantId);

    /**
     * Options for the vehicle form's owner dropdown. Returns an empty list — never throws — when
     * there is no directory, so the form simply renders without the field instead of erroring.
     */
    List<FleetParty> options(Long tenantId);

    /** Whether this deployment has a party directory to pick from. */
    boolean supportsDirectory();
}
