package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.fleet.dto.FleetOptionDto;
import com.crm.travelcrm.fleet.dto.FleetVehicleRequestDto;
import com.crm.travelcrm.fleet.dto.FleetVehicleResponseDto;

import java.util.List;
import java.util.UUID;

public interface FleetVehicleService {

    FleetVehicleResponseDto create(FleetVehicleRequestDto request);

    FleetVehicleResponseDto update(UUID publicId, FleetVehicleRequestDto request);

    FleetVehicleResponseDto getByPublicId(UUID publicId);

    PagedApiResponse<FleetVehicleResponseDto> list(
            String status, String ownerType, String search, int page, int size);

    FleetVehicleResponseDto changeStatus(UUID publicId, String status);

    void delete(UUID publicId);

    /** Slim dropdown options for trip/log forms; optional status filter (e.g. AVAILABLE). */
    List<FleetOptionDto> options(String status);

    /** Active vendors as dropdown options — for linking VENDOR/RENTED vehicles. */
    List<FleetOptionDto> vendorOptions();
}