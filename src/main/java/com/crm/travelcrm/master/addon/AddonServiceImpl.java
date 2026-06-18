package com.crm.travelcrm.master.addon;

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
import org.springframework.util.StringUtils;

@Service
@Slf4j
@RequiredArgsConstructor
public class AddonServiceImpl implements AddonService {

    private final AddonRepository addonRepository;
    private final CityRepository cityRepository;
    private final AddonMapper addonMapper;

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<AddonDto> getAll(int page, int size, String sortBy, String sortDir) {
        Long tenantId = GeographySupport.currentTenantId();
        Page<Addon> result = addonRepository.findByTenantId(
                tenantId, PageRequest.of(page, size, GeographySupport.buildSort(sortBy, sortDir)));
        return PagedApiResponse.of("Addons fetched",
                result.map(addonMapper::toDto).getContent(),
                PaginationMeta.from(result, sortBy, sortDir));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<AddonDto> getByCity(Long cityId, int page, int size, String sortBy, String sortDir) {
        Long tenantId = GeographySupport.currentTenantId();
        Page<Addon> result = addonRepository.findByTenantIdAndCityId(
                tenantId, cityId, PageRequest.of(page, size, GeographySupport.buildSort(sortBy, sortDir)));
        return PagedApiResponse.of("Addons fetched",
                result.map(addonMapper::toDto).getContent(),
                PaginationMeta.from(result, sortBy, sortDir));
    }

    @Override
    @Transactional(readOnly = true)
    public AddonDto getById(Long id) {
        return addonMapper.toDto(findOrThrow(id));
    }

    @Override
    @Transactional
    public AddonDto create(CreateAddonRequest request) {
        Long tenantId = GeographySupport.currentTenantId();
        Addon addon = addonMapper.toEntity(request);
        if (request.getCityId() != null) {
            addon.setCity(resolveCity(request.getCityId(), tenantId));
        }
        addon.setTenantId(tenantId);
        Addon saved = addonRepository.save(addon);
        log.info("Addon created | id: {} | tenantId: {}", saved.getId(), tenantId);
        return addonMapper.toDto(saved);
    }

    @Override
    @Transactional
    public AddonDto update(Long id, UpdateAddonRequest request) {
        Long tenantId = GeographySupport.currentTenantId();
        Addon addon = findOrThrow(id);
        if (request.getCityId() != null) {
            addon.setCity(resolveCity(request.getCityId(), tenantId));
        }
        if (request.getActive() != null) {
            addon.setActive(request.getActive());
        }
        addonMapper.updateEntity(request, addon);
        return addonMapper.toDto(addonRepository.save(addon));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Addon addon = findOrThrow(id);
        addonRepository.delete(addon);
        log.info("Addon deleted | id: {}", id);
    }

    private Addon findOrThrow(Long id) {
        Long tenantId = GeographySupport.currentTenantId();
        return addonRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Addon not found: " + id));
    }

    private City resolveCity(Long cityId, Long tenantId) {
        return cityRepository.findByIdAndTenantId(cityId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("City not found: " + cityId));
    }
}