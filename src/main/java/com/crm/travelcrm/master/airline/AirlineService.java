package com.crm.travelcrm.master.airline;

import com.crm.travelcrm.common.dto.PagedApiResponse;

public interface AirlineService {

    PagedApiResponse<AirlineDto> getAll(int page, int size, String sortBy, String sortDir);

    PagedApiResponse<AirlineDto> getByCity(Long cityId, int page, int size, String sortBy, String sortDir);

    AirlineDto getById(Long id);

    AirlineDto create(CreateAirlineRequest request);

    AirlineDto update(Long id, UpdateAirlineRequest request);

    void delete(Long id);
}