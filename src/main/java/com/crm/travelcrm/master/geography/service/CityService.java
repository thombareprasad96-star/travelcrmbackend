package com.crm.travelcrm.master.geography.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.master.geography.dto.request.CreateCityRequest;
import com.crm.travelcrm.master.geography.dto.request.UpdateCityRequest;
import com.crm.travelcrm.master.geography.dto.response.CityDto;

public interface CityService {

    PagedApiResponse<CityDto> getByDestination(
            Long destinationId, int page, int size, String sortBy, String sortDir);

    CityDto getById(Long cityId);

    CityDto create(Long destinationId, CreateCityRequest request);

    CityDto update(Long cityId, UpdateCityRequest request);

    void delete(Long cityId);
}