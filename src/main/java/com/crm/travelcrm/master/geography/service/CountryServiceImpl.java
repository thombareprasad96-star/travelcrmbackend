package com.crm.travelcrm.master.geography.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.master.geography.dto.request.CreateCountryRequest;
import com.crm.travelcrm.master.geography.dto.request.UpdateCountryRequest;
import com.crm.travelcrm.master.geography.dto.response.CountryDto;
import com.crm.travelcrm.master.geography.entity.Country;
import com.crm.travelcrm.master.geography.mapper.CountryMapper;
import com.crm.travelcrm.master.geography.repository.CountryRepository;
import com.crm.travelcrm.master.geography.support.GeographySupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Slf4j
@RequiredArgsConstructor
public class CountryServiceImpl implements CountryService {

    private final CountryRepository countryRepository;
    private final CountryMapper countryMapper;

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<CountryDto> getAll(int page, int size, String sortBy, String sortDir) {
        Long tenantId = GeographySupport.currentTenantId();
        Page<Country> result = countryRepository.findAllByTenantId(
                tenantId, PageRequest.of(page, size, GeographySupport.buildSort(sortBy, sortDir)));
        return PagedApiResponse.of(
                "Countries fetched successfully",
                result.map(countryMapper::toDto).getContent(),
                PaginationMeta.from(result, sortBy, sortDir));
    }

    @Override
    @Transactional(readOnly = true)
    public CountryDto getById(Long countryId) {
        return countryMapper.toDto(findOrThrow(countryId));
    }

    @Override
    @Transactional
    public CountryDto create(CreateCountryRequest request) {
        Long tenantId = GeographySupport.currentTenantId();

        String name = request.getName().trim();
        String code = request.getCode().trim().toUpperCase();

        if (countryRepository.existsByTenantIdAndName(tenantId, name)) {
            throw new BusinessException("A country named '" + name + "' already exists", HttpStatus.CONFLICT);
        }
        if (countryRepository.existsByTenantIdAndCode(tenantId, code)) {
            throw new BusinessException("A country with code '" + code + "' already exists", HttpStatus.CONFLICT);
        }

        Country country = countryMapper.toEntity(request);
        country.setName(name);
        country.setCode(code);
        country.setTenantId(tenantId);

        Country saved = countryRepository.save(country);
        log.info("Country created | id: {} | code: {} | tenantId: {}", saved.getId(), code, tenantId);
        return countryMapper.toDto(saved);
    }

    @Override
    @Transactional
    public CountryDto update(Long countryId, UpdateCountryRequest request) {
        Long tenantId = GeographySupport.currentTenantId();
        Country country = findOrThrow(countryId);

        if (StringUtils.hasText(request.getName())) {
            String name = request.getName().trim();
            if (countryRepository.existsByTenantIdAndNameAndIdNot(tenantId, name, countryId)) {
                throw new BusinessException("A country named '" + name + "' already exists", HttpStatus.CONFLICT);
            }
        }
        if (StringUtils.hasText(request.getCode())) {
            String code = request.getCode().trim().toUpperCase();
            if (countryRepository.existsByTenantIdAndCodeAndIdNot(tenantId, code, countryId)) {
                throw new BusinessException("A country with code '" + code + "' already exists", HttpStatus.CONFLICT);
            }
            request.setCode(code);
        }

        countryMapper.updateEntity(request, country);
        Country saved = countryRepository.save(country);
        log.info("Country updated | id: {} | tenantId: {}", countryId, tenantId);
        return countryMapper.toDto(saved);
    }

    @Override
    @Transactional
    public void delete(Long countryId) {
        Country country = findOrThrow(countryId);
        // Hard delete — JPA cascades to destinations → cities (and their children).
        countryRepository.delete(country);
        log.info("Country deleted | id: {} | tenantId: {}", countryId, country.getTenantId());
    }

    private Country findOrThrow(Long countryId) {
        Long tenantId = GeographySupport.currentTenantId();
        return countryRepository.findByIdAndTenantId(countryId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Country not found: " + countryId));
    }
}