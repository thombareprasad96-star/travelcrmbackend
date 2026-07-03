package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.fleet.dto.*;

import java.time.LocalDate;
import java.util.UUID;

public interface FleetTripService {

    FleetTripResponseDto create(FleetTripCreateDto request);

    FleetTripResponseDto update(UUID publicId, FleetTripUpdateDto request);

    FleetTripResponseDto getByPublicId(UUID publicId);

    PagedApiResponse<FleetTripResponseDto> list(
            UUID vehiclePublicId, UUID driverPublicId, String status, UUID bookingPublicId,
            LocalDate fromDate, LocalDate toDate, String search, int page, int size);

    /** PLANNED → ONGOING; records the start odometer and flips the vehicle to ON_TRIP. */
    FleetTripResponseDto start(UUID publicId, FleetTripStartDto request);

    /** ONGOING → COMPLETED; records end odometer/time, computes distance, frees the vehicle. */
    FleetTripResponseDto close(UUID publicId, FleetTripCloseDto request);

    /** PLANNED/ONGOING → CANCELLED; frees the vehicle if it was on this trip. */
    FleetTripResponseDto cancel(UUID publicId);

    void delete(UUID publicId);
}