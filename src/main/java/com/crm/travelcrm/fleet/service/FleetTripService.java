package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.fleet.dto.*;

import java.time.LocalDate;
import java.util.List;
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

    /**
     * Hand a running trip over to another vehicle and/or driver — a breakdown, a relief driver, a
     * reallocation. Closes the current leg and opens the next one, so the odometer chain and the
     * "who was driving at 14:20" answer both survive.
     */
    FleetTripResponseDto swap(UUID publicId, FleetTripSwapDto request);

    /**
     * The trip broken into its legs. A single-leg trip reads like the trip itself; more than one
     * means the duty changed hands, and this is the only place the earlier vehicle, its odometer span
     * and its driver survive.
     */
    List<FleetTripLegDto> legs(UUID publicId);

    /** Handover vocabulary for the swap form — served so the frontend keeps no copy of the enum. */
    List<FleetLegChangeReasonDto> legChangeReasons();

    /**
     * The printable duty slip — the paper that rides with the vehicle.
     *
     * <p>Printable from PLANNED onward on purpose: the slip leaves the office half-empty, the driver
     * writes the readings in, and the guest signs it at release. Unknown fields render as ruled
     * blanks rather than zeros.
     */
    byte[] dutySlip(UUID publicId);

    void delete(UUID publicId);
}