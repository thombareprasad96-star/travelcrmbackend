package com.crm.travelcrm.master.sightseeing;

import com.crm.travelcrm.common.cloudinary.CloudinaryService;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.master.geography.entity.City;
import com.crm.travelcrm.master.geography.repository.CityRepository;
import com.crm.travelcrm.master.geography.support.GeographySupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class SightseeingServiceImpl implements SightseeingService {

    private final SightseeingRepository sightseeingRepository;
    private final CityRepository cityRepository;
    private final SightseeingMapper sightseeingMapper;
    private final CloudinaryService cloudinaryService;

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<SightseeingDto> getAll(int page, int size, String sortBy, String sortDir) {
        Long tenantId = GeographySupport.currentTenantId();
        Page<Sightseeing> result = sightseeingRepository.findByTenantId(
                tenantId, PageRequest.of(page, size, GeographySupport.buildSort(sortBy, sortDir)));
        return PagedApiResponse.of("Sightseeings fetched",
                result.map(sightseeingMapper::toDto).getContent(),
                PaginationMeta.from(result, sortBy, sortDir));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<SightseeingDto> filter(String destination, String city, int page, int size, String sortBy, String sortDir) {
        Long tenantId = GeographySupport.currentTenantId();
        Page<Sightseeing> result = sightseeingRepository.filterByNames(
                tenantId,
                (destination != null && !destination.isBlank()) ? destination : null,
                (city != null && !city.isBlank()) ? city : null,
                PageRequest.of(page, size, GeographySupport.buildSort(sortBy, sortDir)));
        return PagedApiResponse.of("Sightseeings fetched",
                result.map(sightseeingMapper::toDto).getContent(),
                PaginationMeta.from(result, sortBy, sortDir));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<SightseeingDto> getByCity(Long cityId, int page, int size, String sortBy, String sortDir) {
        Long tenantId = GeographySupport.currentTenantId();
        Page<Sightseeing> result = sightseeingRepository.findByTenantIdAndCityId(
                tenantId, cityId, PageRequest.of(page, size, GeographySupport.buildSort(sortBy, sortDir)));
        return PagedApiResponse.of("Sightseeings fetched",
                result.map(sightseeingMapper::toDto).getContent(),
                PaginationMeta.from(result, sortBy, sortDir));
    }

    @Transactional(readOnly = true)
    public PagedApiResponse<SightseeingDto> getByDestionation(Long DestionationId, int page, int size, String sortBy, String sortDir) {
        Long tenantId = GeographySupport.currentTenantId();
        Page<Sightseeing> result = sightseeingRepository.findByTenantIdAndDestinationId(
                tenantId, DestionationId, PageRequest.of(page, size, GeographySupport.buildSort(sortBy, sortDir)));
        return PagedApiResponse.of("Sightseeings fetched",
                result.map(sightseeingMapper::toDto).getContent(),
                PaginationMeta.from(result, sortBy, sortDir));
    }

    @Override
    @Transactional(readOnly = true)
    public SightseeingDto getById(Long id) {
        return sightseeingMapper.toDto(findOrThrow(id));
    }

    @Override
    @Transactional
    public SightseeingDto create(CreateSightseeingRequest request) {
        Long tenantId = GeographySupport.currentTenantId();
        City city = resolveCityByName(request.getDestination(), request.getCity(), tenantId);

        Sightseeing entity = sightseeingMapper.toEntity(request);
        entity.setCity(city);
        entity.setTenantId(tenantId);

        Sightseeing saved = sightseeingRepository.save(entity);
        log.info("Sightseeing created | id: {} | cityId: {} | tenantId: {}", saved.getId(), city.getId(), tenantId);
        return sightseeingMapper.toDto(saved);
    }

    @Override
    @Transactional
    public SightseeingDto update(Long id, UpdateSightseeingRequest request) {
        Long tenantId = GeographySupport.currentTenantId();
        Sightseeing entity = findOrThrow(id);

        if (StringUtils.hasText(request.getDestination()) || StringUtils.hasText(request.getCity())) {
            City city = resolveCityByName(request.getDestination(), request.getCity(), tenantId);
            entity.setCity(city);
        }

        sightseeingMapper.updateEntity(request, entity);
        return sightseeingMapper.toDto(sightseeingRepository.save(entity));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Sightseeing entity = findOrThrow(id);
        // Leaf master — referenced by quotations only via name snapshot (no FK). Recoverable.
        entity.softDelete(GeographySupport.currentUsername());
        sightseeingRepository.save(entity);
        log.info("Sightseeing moved to Trash | id: {}", id);
    }

    @Override
    public String uploadImage(MultipartFile file) {
        return cloudinaryService.uploadImage(file, "sightseeings");
    }

    @Override
    @Transactional(readOnly = true)
    public List<SightseeingDto> search(String q) {
        Long tenantId = GeographySupport.currentTenantId();
        return sightseeingRepository.searchByTitle(tenantId, q)
                .stream().map(sightseeingMapper::toDto).collect(Collectors.toList());
    }

    private Sightseeing findOrThrow(Long id) {
        Long tenantId = GeographySupport.currentTenantId();
        return sightseeingRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Sightseeing not found: " + id));
    }

    private City resolveCityByName(String destinationName, String cityName, Long tenantId) {
        if (!StringUtils.hasText(destinationName) || !StringUtils.hasText(cityName)) {
            throw new BusinessException("destination and city are required", HttpStatus.BAD_REQUEST);
        }
        return cityRepository.findByTenantIdAndDestination_NameIgnoreCaseAndNameIgnoreCase(
                        tenantId, destinationName.trim(), cityName.trim())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "City '" + cityName + "' not found under destination '" + destinationName + "'"));
    }
}