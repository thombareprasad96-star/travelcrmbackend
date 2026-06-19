package com.crm.travelcrm.master.dropdown;

import com.crm.travelcrm.common.dto.DropdownDto;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.master.addon.AddonRepository;
import com.crm.travelcrm.master.airline.AirlineRepository;
import com.crm.travelcrm.master.cruise.CruiseRepository;
import com.crm.travelcrm.master.cruise.CruiseRoomTypeRepository;
import com.crm.travelcrm.master.geography.repository.CityRepository;
import com.crm.travelcrm.master.geography.repository.CountryRepository;
import com.crm.travelcrm.master.geography.repository.DestinationRepository;
import com.crm.travelcrm.master.geography.support.GeographySupport;
import com.crm.travelcrm.master.hotel.HotelRepository;
import com.crm.travelcrm.master.hotel.MealPlanRepository;
import com.crm.travelcrm.master.hotel.RoomTypeRepository;
import com.crm.travelcrm.master.sightseeing.SightseeingRepository;
import com.crm.travelcrm.master.vehicle.VehicleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class MasterDropdownServiceImpl implements MasterDropdownService {

    private final CountryRepository       countryRepository;
    private final DestinationRepository   destinationRepository;
    private final CityRepository          cityRepository;
    private final HotelRepository         hotelRepository;
    private final RoomTypeRepository      roomTypeRepository;
    private final MealPlanRepository      mealPlanRepository;
    private final SightseeingRepository   sightseeingRepository;
    private final VehicleRepository       vehicleRepository;
    private final AddonRepository         addonRepository;
    private final AirlineRepository       airlineRepository;
    private final CruiseRepository        cruiseRepository;
    private final CruiseRoomTypeRepository cruiseRoomTypeRepository;

    // ── Geography ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<DropdownDto> getCountries() {
        Long tenantId = GeographySupport.currentTenantId();
        return countryRepository.findAllByTenantIdOrderByNameAsc(tenantId)
                .stream()
                .map(c -> DropdownDto.builder().value(c.getId()).label(c.getName()).build())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DropdownDto> getDestinations(Long countryId) {
        Long tenantId = GeographySupport.currentTenantId();
        // No country filter → every destination the tenant can see (the tenant
        // works with a fixed set of destinations; no country pre-selection).
        List<com.crm.travelcrm.master.geography.entity.Destination> destinations;
        if (countryId != null) {
            countryRepository.findByIdAndTenantId(countryId, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Country not found: " + countryId));
            destinations = destinationRepository.findActiveByCountryIdVisibleTo(tenantId, countryId);
        } else {
            destinations = destinationRepository.findAllActiveVisibleTo(tenantId);
        }
        return destinations.stream()
                .map(d -> DropdownDto.builder().value(d.getId()).label(d.getName()).build())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DropdownDto> getCities(Long destinationId) {
        Long tenantId = GeographySupport.currentTenantId();
        destinationRepository.findByIdVisibleTo(destinationId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Destination not found: " + destinationId));
        return cityRepository.findByTenantIdAndDestinationIdOrderByNameAsc(tenantId, destinationId)
                .stream()
                .map(c -> DropdownDto.builder().value(c.getId()).label(c.getName()).build())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DropdownDto> getCitiesByCountry(Long countryId) {
        Long tenantId = GeographySupport.currentTenantId();
        countryRepository.findByIdAndTenantId(countryId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Country not found: " + countryId));
        return cityRepository.findByTenantIdAndCountryIdOrderByNameAsc(tenantId, countryId)
                .stream()
                .map(c -> DropdownDto.builder().value(c.getId()).label(c.getName()).build())
                .toList();
    }

    // ── Hotel hierarchy ───────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<DropdownDto> getHotels(Long destinationId) {
        Long tenantId = GeographySupport.currentTenantId();
        List<?> hotels = (destinationId != null)
                ? hotelRepository.findByTenantIdAndDestinationIdForDropdown(tenantId, destinationId)
                : hotelRepository.findAllByTenantIdForDropdown(tenantId);
        return hotels.stream()
                .map(h -> {
                    com.crm.travelcrm.master.hotel.Hotel hotel = (com.crm.travelcrm.master.hotel.Hotel) h;
                    return DropdownDto.builder().value(hotel.getId()).label(hotel.getName()).build();
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DropdownDto> getRoomTypes(Long hotelId) {
        return roomTypeRepository.findByHotelIdOrderByNameAsc(hotelId)
                .stream()
                .map(r -> DropdownDto.builder().value(r.getId()).label(r.getName()).build())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DropdownDto> getMealPlans(Long hotelId) {
        return mealPlanRepository.findByHotelIdOrderByNameAsc(hotelId)
                .stream()
                .map(m -> DropdownDto.builder().value(m.getId()).label(m.getName()).build())
                .toList();
    }

    // ── Sightseeing ───────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<DropdownDto> getSightseeings(Long destinationId) {
        Long tenantId = GeographySupport.currentTenantId();
        List<?> list = (destinationId != null)
                ? sightseeingRepository.findByTenantIdAndDestinationIdForDropdown(tenantId, destinationId)
                : sightseeingRepository.findAllByTenantIdForDropdown(tenantId);
        return list.stream()
                .map(s -> {
                    com.crm.travelcrm.master.sightseeing.Sightseeing sg =
                            (com.crm.travelcrm.master.sightseeing.Sightseeing) s;
                    return DropdownDto.builder().value(sg.getId()).label(sg.getTitle()).build();
                })
                .toList();
    }

    // ── Vehicle ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<DropdownDto> getVehicles() {
        Long tenantId = GeographySupport.currentTenantId();
        return vehicleRepository.findAllVisibleToOrderByNameAsc(tenantId)
                .stream()
                .map(v -> DropdownDto.builder().value(v.getId()).label(v.getName()).build())
                .toList();
    }

    // ── Addon ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<DropdownDto> getAddons() {
        Long tenantId = GeographySupport.currentTenantId();
        return addonRepository.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId)
                .stream()
                .map(a -> DropdownDto.builder().value(a.getId()).label(a.getName()).build())
                .toList();
    }

    // ── Airline ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<DropdownDto> getAirlines() {
        Long tenantId = GeographySupport.currentTenantId();
        return airlineRepository.findByTenantIdOrderByNameAsc(tenantId)
                .stream()
                .map(a -> {
                    // Include IATA code in label when available: "Air India (AI)"
                    String label = (a.getIata() != null && !a.getIata().isBlank())
                            ? a.getName() + " (" + a.getIata() + ")"
                            : a.getName();
                    return DropdownDto.builder().value(a.getId()).label(label).build();
                })
                .toList();
    }

    // ── Cruise ────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<DropdownDto> getCruises() {
        Long tenantId = GeographySupport.currentTenantId();
        return cruiseRepository.findByTenantIdOrderByNameAsc(tenantId)
                .stream()
                .map(c -> DropdownDto.builder().value(c.getId()).label(c.getName()).build())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DropdownDto> getCruiseRoomTypes(Long cruiseId) {
        return cruiseRoomTypeRepository.findByCruiseIdOrderByNameAsc(cruiseId)
                .stream()
                .map(r -> DropdownDto.builder().value(r.getId()).label(r.getName()).build())
                .toList();
    }
}