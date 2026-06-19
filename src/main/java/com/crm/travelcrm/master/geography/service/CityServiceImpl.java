package com.crm.travelcrm.master.geography.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.master.geography.dto.request.CreateCityRequest;
import com.crm.travelcrm.master.geography.dto.request.UpdateCityRequest;
import com.crm.travelcrm.master.geography.dto.response.CityDto;
import com.crm.travelcrm.master.geography.entity.City;
import com.crm.travelcrm.master.geography.entity.Country;
import com.crm.travelcrm.master.geography.entity.Destination;
import com.crm.travelcrm.master.geography.mapper.CityMapper;
import com.crm.travelcrm.master.geography.repository.CityRepository;
import com.crm.travelcrm.master.geography.repository.CountryRepository;
import com.crm.travelcrm.master.geography.repository.DestinationRepository;
import com.crm.travelcrm.master.geography.support.GeographySupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Slf4j
@RequiredArgsConstructor
public class CityServiceImpl implements CityService {

    private final CityRepository cityRepository;
    private final CountryRepository countryRepository;
    private final DestinationRepository destinationRepository;
    private final CityMapper cityMapper;

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<CityDto> getAll(int page, int size, String sortBy, String sortDir) {
        Long tenantId = GeographySupport.currentTenantId();
        Pageable pageable = PageRequest.of(page, size, GeographySupport.buildSort(sortBy, sortDir));
        Page<City> result = cityRepository.findAllByTenantId(tenantId, pageable);
        return PagedApiResponse.of(
                "Cities fetched successfully",
                result.map(cityMapper::toDto).getContent(),
                PaginationMeta.from(result, sortBy, sortDir));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<CityDto> getByCountry(
            Long countryId, int page, int size, String sortBy, String sortDir) {

        Long tenantId = GeographySupport.currentTenantId();
        resolveCountry(countryId, tenantId);

        Pageable pageable = PageRequest.of(page, size, GeographySupport.buildSort(sortBy, sortDir));
        Page<City> result = cityRepository.findByTenantIdAndCountryId(tenantId, countryId, pageable);

        return PagedApiResponse.of(
                "Cities fetched successfully",
                result.map(cityMapper::toDto).getContent(),
                PaginationMeta.from(result, sortBy, sortDir));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<CityDto> getByDestination(
            Long destinationId, int page, int size, String sortBy, String sortDir) {

        Long tenantId = GeographySupport.currentTenantId();
        resolveDestination(destinationId, tenantId);

        Pageable pageable = PageRequest.of(page, size, GeographySupport.buildSort(sortBy, sortDir));
        Page<City> result = cityRepository.findByTenantIdAndDestinationId(tenantId, destinationId, pageable);

        return PagedApiResponse.of(
                "Cities fetched successfully",
                result.map(cityMapper::toDto).getContent(),
                PaginationMeta.from(result, sortBy, sortDir));
    }

    @Override
    @Transactional(readOnly = true)
    public CityDto getById(Long cityId) {
        return cityMapper.toDto(findOrThrow(cityId));
    }

    /**
     * Flat create ({@code POST /api/cities}). Country is required (via countryId or
     * country name in the body); destination is optional (via destinationId).
     */
    @Override
    @Transactional
    public CityDto createFlat(CreateCityRequest request) {
        Long tenantId = GeographySupport.currentTenantId();

        Country country = resolveCountryFromRequest(request.getCountryId(), request.getCountry(), tenantId);
        Destination destination = resolveOptionalDestination(request.getDestinationId(), country, tenantId);

        return doCreate(request, country, destination, tenantId);
    }

    /**
     * Nested create ({@code POST /api/v1/destinations/{destinationId}/cities}).
     * Country is derived from the destination.
     */
    @Override
    @Transactional
    public CityDto create(Long destinationId, CreateCityRequest request) {
        Long tenantId = GeographySupport.currentTenantId();
        Destination destination = resolveDestination(destinationId, tenantId);
        Country country = destination.getCountry();
        if (country == null) {
            throw new BusinessException(
                    "Destination " + destinationId + " has no country", HttpStatus.CONFLICT);
        }
        return doCreate(request, country, destination, tenantId);
    }

    private CityDto doCreate(
            CreateCityRequest request, Country country, Destination destination, Long tenantId) {

        String name = request.getName().trim();
        if (cityRepository.existsByTenantIdAndCountryIdAndName(tenantId, country.getId(), name)) {
            throw new BusinessException(
                    "A city named '" + name + "' already exists in this country", HttpStatus.CONFLICT);
        }

        City city = cityMapper.toEntity(request);
        city.setName(name);
        city.setCountry(country);
        city.setDestination(destination);   // may be null
        city.setTenantId(tenantId);
        if (StringUtils.hasText(request.getCode())) {
            city.setCode(request.getCode().toUpperCase().trim());
        }

        City saved = cityRepository.save(city);
        log.info("City created | id: {} | countryId: {} | destinationId: {} | tenantId: {}",
                saved.getId(), country.getId(),
                destination != null ? destination.getId() : null, tenantId);
        return cityMapper.toDto(saved);
    }

    @Override
    @Transactional
    public CityDto update(Long cityId, UpdateCityRequest request) {
        Long tenantId = GeographySupport.currentTenantId();
        City city = findOrThrow(cityId);

        // Re-assign country if requested (id or name).
        if (request.getCountryId() != null || StringUtils.hasText(request.getCountry())) {
            city.setCountry(resolveCountryFromRequest(request.getCountryId(), request.getCountry(), tenantId));
        }

        // Re-link destination if requested; validate it belongs to the city's country.
        if (request.getDestinationId() != null) {
            city.setDestination(resolveOptionalDestination(
                    request.getDestinationId(), city.getCountry(), tenantId));
        }

        if (StringUtils.hasText(request.getName())) {
            String name = request.getName().trim();
            if (cityRepository.existsByTenantIdAndCountryIdAndNameAndIdNot(
                    tenantId, city.getCountry().getId(), name, cityId)) {
                throw new BusinessException(
                        "A city named '" + name + "' already exists in this country", HttpStatus.CONFLICT);
            }
            city.setName(name);
        }
        if (StringUtils.hasText(request.getCode())) {
            city.setCode(request.getCode().toUpperCase().trim());
        }

        cityMapper.updateEntity(request, city);
        City saved = cityRepository.save(city);
        log.info("City updated | id: {} | tenantId: {}", cityId, tenantId);
        return cityMapper.toDto(saved);
    }

    @Override
    @Transactional
    public void delete(Long cityId) {
        City city = findOrThrow(cityId);
        cityRepository.delete(city); // cascades to hotels/vehicles/airlines/cruises once those exist
        log.info("City deleted | id: {} | tenantId: {}", cityId, city.getTenantId());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private City findOrThrow(Long cityId) {
        Long tenantId = GeographySupport.currentTenantId();
        return cityRepository.findByIdAndTenantId(cityId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("City not found: " + cityId));
    }

    private Country resolveCountry(Long countryId, Long tenantId) {
        return countryRepository.findByIdAndTenantId(countryId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Country not found: " + countryId));
    }

    /** Resolve the required country from either an id or a name; fail if neither is usable. */
    private Country resolveCountryFromRequest(Long countryId, String countryName, Long tenantId) {
        if (countryId != null) {
            return resolveCountry(countryId, tenantId);
        }
        if (StringUtils.hasText(countryName)) {
            return countryRepository.findByTenantIdAndName(tenantId, countryName.trim())
                    .orElseThrow(() -> new ResourceNotFoundException("Country not found: " + countryName));
        }
        throw new BusinessException("Country is required (countryId or country name)", HttpStatus.BAD_REQUEST);
    }

    private Destination resolveDestination(Long destinationId, Long tenantId) {
        return destinationRepository.findByIdAndTenantId(destinationId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Destination not found: " + destinationId));
    }

    /**
     * Resolve an optional destination and enforce that it belongs to {@code country}.
     * Returns null when {@code destinationId} is null (city left unlinked).
     */
    private Destination resolveOptionalDestination(Long destinationId, Country country, Long tenantId) {
        if (destinationId == null) {
            return null;
        }
        Destination destination = resolveDestination(destinationId, tenantId);
        if (destination.getCountry() == null
                || country == null
                || destination.getCountry().getId() != country.getId()) {
            throw new BusinessException(
                    "Destination " + destinationId + " does not belong to the city's country",
                    HttpStatus.BAD_REQUEST);
        }
        return destination;
    }
}