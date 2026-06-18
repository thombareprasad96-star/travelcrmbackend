package com.crm.travelcrm.master.geography.dropdown;

import com.crm.travelcrm.common.dto.DropdownDto;

import java.util.List;

/**
 * Superseded by MasterDropdownServiceImpl.
 * This class is retained so the interface compiles but is NOT a Spring bean.
 */
@Deprecated
public class LocationDropdownServiceImpl implements LocationDropdownService {

    @Override public List<DropdownDto> getCountries()                              { return List.of(); }
    @Override public List<DropdownDto> getDestinationsByCountryId(Long countryId)  { return List.of(); }
    @Override public List<DropdownDto> getCitiesByDestinationId(Long destinationId) { return List.of(); }
}