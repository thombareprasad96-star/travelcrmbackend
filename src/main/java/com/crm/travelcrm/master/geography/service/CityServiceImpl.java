package com.crm.travelcrm.master.geography.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.master.geography.dto.request.CreateCityRequest;
import com.crm.travelcrm.master.geography.dto.request.UpdateCityRequest;
import com.crm.travelcrm.master.geography.dto.response.CityDto;
import com.crm.travelcrm.master.geography.entity.City;
import com.crm.travelcrm.master.geography.entity.Destination;
import com.crm.travelcrm.master.geography.mapper.CityMapper;
import com.crm.travelcrm.master.geography.repository.CityRepository;
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
    private final DestinationRepository destinationRepository;
    private final CityMapper cityMapper;

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

    @Override
    @Transactional
    public CityDto create(Long destinationId, CreateCityRequest request) {
        Long tenantId = GeographySupport.currentTenantId();
        Destination destination = resolveDestination(destinationId, tenantId);

        String name = request.getName().trim();
        if (cityRepository.existsByTenantIdAndNameAndDestinationId(tenantId, name, destinationId)) {
            throw new BusinessException(
                    "A city named '" + name + "' already exists for this destination", HttpStatus.CONFLICT);
        }

        City city = cityMapper.toEntity(request);
        city.setName(name);
        city.setDestination(destination);
        city.setTenantId(tenantId);

        City saved = cityRepository.save(city);
        log.info("City created | id: {} | destinationId: {} | tenantId: {}", saved.getId(), destinationId, tenantId);
        return cityMapper.toDto(saved);
    }

    @Override
    @Transactional
    public CityDto update(Long cityId, UpdateCityRequest request) {
        Long tenantId = GeographySupport.currentTenantId();
        City city = findOrThrow(cityId);

        if (StringUtils.hasText(request.getName())) {
            String name = request.getName().trim();
            Long destinationId = city.getDestination().getId();
            if (cityRepository.existsByTenantIdAndNameAndDestinationIdAndIdNot(
                    tenantId, name, destinationId, cityId)) {
                throw new BusinessException(
                        "A city named '" + name + "' already exists for this destination", HttpStatus.CONFLICT);
            }
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

    private City findOrThrow(Long cityId) {
        Long tenantId = GeographySupport.currentTenantId();
        return cityRepository.findByIdAndTenantId(cityId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("City not found: " + cityId));
    }

    private Destination resolveDestination(Long destinationId, Long tenantId) {
        return destinationRepository.findByIdAndTenantId(destinationId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Destination not found: " + destinationId));
    }
}