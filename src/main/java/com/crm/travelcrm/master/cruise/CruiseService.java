package com.crm.travelcrm.master.cruise;

import com.crm.travelcrm.common.dto.PagedApiResponse;

public interface CruiseService {

    PagedApiResponse<CruiseDto> getAll(int page, int size, String sortBy, String sortDir);

    PagedApiResponse<CruiseDto> getByCity(Long cityId, int page, int size, String sortBy, String sortDir);

    CruiseDto getById(Long id);

    CruiseDto create(CreateCruiseRequest request);

    CruiseDto update(Long id, UpdateCruiseRequest request);

    void delete(Long id);

    CruiseRoomTypeDto addRoomType(Long cruiseId, CreateCruiseRoomTypeRequest request);

    CruiseRoomTypeDto updateRoomType(Long cruiseId, Long roomTypeId, UpdateCruiseRoomTypeRequest request);

    void deleteRoomType(Long cruiseId, Long roomTypeId);
}