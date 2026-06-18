package com.crm.travelcrm.master.cruise;

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

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class CruiseServiceImpl implements CruiseService {

    private final CruiseRepository cruiseRepository;
    private final CruiseRoomTypeRepository roomTypeRepository;
    private final CityRepository cityRepository;
    private final CruiseMapper cruiseMapper;

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<CruiseDto> getAll(int page, int size, String sortBy, String sortDir) {
        Long tenantId = GeographySupport.currentTenantId();
        Page<Cruise> result = cruiseRepository.findByTenantId(
                tenantId, PageRequest.of(page, size, GeographySupport.buildSort(sortBy, sortDir)));
        return PagedApiResponse.of("Cruises fetched",
                result.map(cruiseMapper::toDto).getContent(),
                PaginationMeta.from(result, sortBy, sortDir));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<CruiseDto> getByCity(Long cityId, int page, int size, String sortBy, String sortDir) {
        Long tenantId = GeographySupport.currentTenantId();
        Page<Cruise> result = cruiseRepository.findByTenantIdAndCityId(
                tenantId, cityId, PageRequest.of(page, size, GeographySupport.buildSort(sortBy, sortDir)));
        return PagedApiResponse.of("Cruises fetched",
                result.map(cruiseMapper::toDto).getContent(),
                PaginationMeta.from(result, sortBy, sortDir));
    }

    @Override
    @Transactional(readOnly = true)
    public CruiseDto getById(Long id) {
        return cruiseMapper.toDto(findOrThrow(id));
    }

    @Override
    @Transactional
    public CruiseDto create(CreateCruiseRequest request) {
        Long tenantId = GeographySupport.currentTenantId();
        Cruise cruise = cruiseMapper.toEntity(request);
        if (request.getCityId() != null) {
            cruise.setCity(resolveCity(request.getCityId(), tenantId));
        }
        cruise.setTenantId(tenantId);

        if (request.getRoomTypes() != null) {
            List<CruiseRoomType> roomTypes = request.getRoomTypes().stream()
                    .map(rt -> {
                        CruiseRoomType entity = cruiseMapper.toRoomTypeEntity(rt);
                        entity.setCruise(cruise);
                        return entity;
                    }).collect(Collectors.toList());
            cruise.getRoomTypes().addAll(roomTypes);
        }

        Cruise saved = cruiseRepository.save(cruise);
        log.info("Cruise created | id: {} | tenantId: {}", saved.getId(), tenantId);
        return cruiseMapper.toDto(saved);
    }

    @Override
    @Transactional
    public CruiseDto update(Long id, UpdateCruiseRequest request) {
        Long tenantId = GeographySupport.currentTenantId();
        Cruise cruise = findOrThrow(id);
        if (request.getCityId() != null) {
            cruise.setCity(resolveCity(request.getCityId(), tenantId));
        }
        cruiseMapper.updateEntity(request, cruise);
        return cruiseMapper.toDto(cruiseRepository.save(cruise));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Cruise cruise = findOrThrow(id);
        cruiseRepository.delete(cruise);
        log.info("Cruise deleted | id: {}", id);
    }

    @Override
    @Transactional
    public CruiseRoomTypeDto addRoomType(Long cruiseId, CreateCruiseRoomTypeRequest request) {
        Cruise cruise = findOrThrow(cruiseId);
        CruiseRoomType roomType = cruiseMapper.toRoomTypeEntity(request);
        roomType.setCruise(cruise);
        CruiseRoomType saved = roomTypeRepository.save(roomType);
        return cruiseMapper.toRoomTypeDto(saved);
    }

    @Override
    @Transactional
    public CruiseRoomTypeDto updateRoomType(Long cruiseId, Long roomTypeId, UpdateCruiseRoomTypeRequest request) {
        findOrThrow(cruiseId);
        CruiseRoomType roomType = roomTypeRepository.findByIdAndCruiseId(roomTypeId, cruiseId)
                .orElseThrow(() -> new ResourceNotFoundException("Room type not found: " + roomTypeId));
        cruiseMapper.updateRoomTypeEntity(request, roomType);
        return cruiseMapper.toRoomTypeDto(roomTypeRepository.save(roomType));
    }

    @Override
    @Transactional
    public void deleteRoomType(Long cruiseId, Long roomTypeId) {
        findOrThrow(cruiseId);
        CruiseRoomType roomType = roomTypeRepository.findByIdAndCruiseId(roomTypeId, cruiseId)
                .orElseThrow(() -> new ResourceNotFoundException("Room type not found: " + roomTypeId));
        roomTypeRepository.delete(roomType);
    }

    private Cruise findOrThrow(Long id) {
        Long tenantId = GeographySupport.currentTenantId();
        return cruiseRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Cruise not found: " + id));
    }

    private City resolveCity(Long cityId, Long tenantId) {
        return cityRepository.findByIdAndTenantId(cityId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("City not found: " + cityId));
    }
}