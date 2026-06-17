package com.crm.travelcrm.master.geography.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.master.geography.dto.request.CreateCountryRequest;
import com.crm.travelcrm.master.geography.dto.request.UpdateCountryRequest;
import com.crm.travelcrm.master.geography.dto.response.CountryDto;

public interface CountryService {

    PagedApiResponse<CountryDto> getAll(int page, int size, String sortBy, String sortDir);

    CountryDto getById(Long countryId);

    CountryDto create(CreateCountryRequest request);

    CountryDto update(Long countryId, UpdateCountryRequest request);

    void delete(Long countryId);
}