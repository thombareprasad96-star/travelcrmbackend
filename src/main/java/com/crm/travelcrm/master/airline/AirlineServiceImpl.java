package com.crm.travelcrm.master.airline;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.master.geography.entity.City;
import com.crm.travelcrm.master.geography.repository.CityRepository;
import com.crm.travelcrm.master.geography.support.GeographySupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class AirlineServiceImpl implements AirlineService {

    private final AirlineRepository airlineRepository;
    private final CityRepository cityRepository;
    private final AirlineMapper airlineMapper;

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<AirlineDto> getAll(int page, int size, String sortBy, String sortDir) {
        Long tenantId = GeographySupport.currentTenantId();
        Page<Airline> result = airlineRepository.findByTenantId(
                tenantId, PageRequest.of(page, size, GeographySupport.buildSort(sortBy, sortDir)));
        return PagedApiResponse.of("Airlines fetched",
                result.map(airlineMapper::toDto).getContent(),
                PaginationMeta.from(result, sortBy, sortDir));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<AirlineDto> getByCity(Long cityId, int page, int size, String sortBy, String sortDir) {
        Long tenantId = GeographySupport.currentTenantId();
        Page<Airline> result = airlineRepository.findByTenantIdAndCityId(
                tenantId, cityId, PageRequest.of(page, size, GeographySupport.buildSort(sortBy, sortDir)));
        return PagedApiResponse.of("Airlines fetched",
                result.map(airlineMapper::toDto).getContent(),
                PaginationMeta.from(result, sortBy, sortDir));
    }

    @Override
    @Transactional(readOnly = true)
    public AirlineDto getById(Long id) {
        return airlineMapper.toDto(findOrThrow(id));
    }

    @Override
    @Transactional
    public AirlineDto create(CreateAirlineRequest request) {
        Long tenantId = GeographySupport.currentTenantId();
        Airline airline = airlineMapper.toEntity(request);
        if (request.getCityId() != null) {
            airline.setCity(resolveCity(request.getCityId(), tenantId));
        }
        airline.setTenantId(tenantId);
        Airline saved = airlineRepository.save(airline);
        log.info("Airline created | id: {} | tenantId: {}", saved.getId(), tenantId);
        return airlineMapper.toDto(saved);
    }

    @Override
    @Transactional
    public AirlineDto update(Long id, UpdateAirlineRequest request) {
        Long tenantId = GeographySupport.currentTenantId();
        Airline airline = findOrThrow(id);
        if (request.getCityId() != null) {
            airline.setCity(resolveCity(request.getCityId(), tenantId));
        }
        airlineMapper.updateEntity(request, airline);
        return airlineMapper.toDto(airlineRepository.save(airline));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Airline airline = findOrThrow(id);
        airlineRepository.delete(airline);
        log.info("Airline deleted | id: {}", id);
    }

    private Airline findOrThrow(Long id) {
        Long tenantId = GeographySupport.currentTenantId();
        return airlineRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Airline not found: " + id));
    }

    private City resolveCity(Long cityId, Long tenantId) {
        return cityRepository.findByIdAndTenantId(cityId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("City not found: " + cityId));
    }
}