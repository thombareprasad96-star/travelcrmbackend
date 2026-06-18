package com.crm.travelcrm.master.geography.dropdown;

import com.crm.travelcrm.common.dto.DropdownDto;

import java.util.List;

/**
 * Provides lightweight value/label lists for the three cascading dropdowns:
 * Country → Destination → City.
 *
 * <p>Each method is tenant-scoped: the implementation reads the current tenant
 * from {@link com.crm.travelcrm.common.context.TenantContext} (populated by the
 * JWT filter) so callers never pass tenantId explicitly.</p>
 */
public interface LocationDropdownService {

    /**
     * All countries belonging to the current tenant, alphabetically ordered.
     */
    List<DropdownDto> getCountries();

    /**
     * Active destinations under {@code countryId} that are visible to the current
     * tenant (tenant-owned + platform-managed global destinations).
     *
     * @throws com.crm.travelcrm.common.exception.ResourceNotFoundException
     *         when countryId does not exist for this tenant.
     */
    List<DropdownDto> getDestinationsByCountryId(Long countryId);

    /**
     * All cities under {@code destinationId} belonging to the current tenant.
     *
     * @throws com.crm.travelcrm.common.exception.ResourceNotFoundException
     *         when destinationId is not visible to this tenant.
     */
    List<DropdownDto> getCitiesByDestinationId(Long destinationId);
}