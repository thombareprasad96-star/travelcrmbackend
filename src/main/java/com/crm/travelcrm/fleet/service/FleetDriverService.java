package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.fleet.dto.FleetDriverRequestDto;
import com.crm.travelcrm.fleet.dto.FleetDriverResponseDto;
import com.crm.travelcrm.fleet.dto.FleetOptionDto;

import java.util.List;
import java.util.UUID;

public interface FleetDriverService {

    FleetDriverResponseDto create(FleetDriverRequestDto request);

    FleetDriverResponseDto update(UUID publicId, FleetDriverRequestDto request);

    FleetDriverResponseDto getByPublicId(UUID publicId);

    PagedApiResponse<FleetDriverResponseDto> list(String status, String search, int page, int size);

    FleetDriverResponseDto changeStatus(UUID publicId, String status);

    void delete(UUID publicId);

    /** Slim dropdown options for the trip form; optional status filter (default all). */
    List<FleetOptionDto> options(String status);
}