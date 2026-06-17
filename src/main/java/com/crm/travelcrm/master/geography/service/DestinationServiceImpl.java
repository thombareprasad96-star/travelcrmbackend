package com.crm.travelcrm.master.geography.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.master.geography.dto.request.CreateDestinationRequest;
import com.crm.travelcrm.master.geography.dto.request.UpdateDestinationRequest;
import com.crm.travelcrm.master.geography.dto.response.DestinationDto;
import com.crm.travelcrm.master.geography.dto.response.DestinationListResponseDTO;
import com.crm.travelcrm.master.geography.entity.Country;
import com.crm.travelcrm.master.geography.entity.Destination;
import com.crm.travelcrm.master.geography.mapper.DestinationMapper;
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
public class DestinationServiceImpl implements DestinationService {

    private final DestinationRepository destinationRepository;
    private final CountryRepository     countryRepository;
    private final DestinationMapper     destinationMapper;   // injected Spring bean — NOT static

    // ── List ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<DestinationListResponseDTO> getAllDestinations(
            int page, int size, String sortBy, String sortDir) {

        Pageable pageable = PageRequest.of(page, size, GeographySupport.buildSort(sortBy, sortDir));
        Long tenantId = GeographySupport.currentTenantId();

        // SuperAdmin (tenantId == null) sees everything; tenants see global + their own.
        Page<Destination> destinationPage = (tenantId == null)
                ? destinationRepository.findAll(pageable)
                : destinationRepository.findAllVisibleTo(tenantId, pageable);

        // FIX: instance method call on injected mapper bean, not static reference
        return destinationPage.map(destinationMapper::toListResponseDTO);
    }

    // ── By country ───────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<DestinationDto> getByCountry(
            Long countryId, int page, int size, String sortBy, String sortDir) {

        Long tenantId = GeographySupport.currentTenantId();
        resolveCountry(countryId, tenantId);   // 404 if country not visible to this tenant

        Pageable pageable = PageRequest.of(page, size, GeographySupport.buildSort(sortBy, sortDir));

        // FIX: added missing semicolon
        Page<Destination> result =
                destinationRepository.findByTenantIdAndCountryId(tenantId, countryId, pageable);

        return PagedApiResponse.of(
                "Destinations fetched successfully",
                result.map(destinationMapper::toDto).getContent(),
                PaginationMeta.from(result, sortBy, sortDir));
    }

    // ── Single item ───────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public DestinationDto getById(Long destinationId) {
        return destinationMapper.toDto(findOrThrow(destinationId));
    }

    // ── Create (nested: country in path) ─────────────────────────────────────

    @Override
    @Transactional
    public DestinationDto create(Long countryId, CreateDestinationRequest request) {
        Long tenantId = GeographySupport.currentTenantId();
        Country country = resolveCountry(countryId, tenantId);
        return doCreate(request, country, tenantId);
    }

    // ── Create (flat: countryId in body) ─────────────────────────────────────

    @Override
    @Transactional
    public DestinationDto create(CreateDestinationRequest request) {
        if (request.getCountryId() == null) {
            throw new BusinessException("countryId is required", HttpStatus.BAD_REQUEST);
        }
        Long tenantId = GeographySupport.currentTenantId();
        Country country = resolveCountry(request.getCountryId(), tenantId);
        return doCreate(request, country, tenantId);
    }

    // ── Shared create logic ───────────────────────────────────────────────────

    private DestinationDto doCreate(
            CreateDestinationRequest request, Country country, Long tenantId) {

        String name = request.getName().trim();
        if (destinationRepository.existsByTenantIdAndName(tenantId, name)) {
            throw new BusinessException(
                    "A destination named '" + name + "' already exists", HttpStatus.CONFLICT);
        }

        Destination destination = destinationMapper.toEntity(request);
        destination.setName(name);
        destination.setCountry(country);
        destination.setTenantId(tenantId);
        // PLATFORM_ADMIN creates global destinations; tenant users create their own
        destination.setGlobal(tenantId == null);

        Destination saved = destinationRepository.save(destination);
        log.info("Destination created | id={} | countryId={} | tenantId={} | global={}",
                saved.getId(), country.getId(), tenantId, saved.isGlobal());
        return destinationMapper.toDto(saved);
    }

    // ── Update ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public DestinationDto update(Long destinationId, UpdateDestinationRequest request) {
        Long tenantId = GeographySupport.currentTenantId();
        Destination destination = findOrThrow(destinationId);

        if (StringUtils.hasText(request.getName())) {
            String name = request.getName().trim();
            if (destinationRepository.existsByTenantIdAndNameAndIdNot(tenantId, name, destinationId)) {
                throw new BusinessException(
                        "A destination named '" + name + "' already exists", HttpStatus.CONFLICT);
            }
        }

        destinationMapper.updateEntity(request, destination);
        Destination saved = destinationRepository.save(destination);
        log.info("Destination updated | id={} | tenantId={}", destinationId, tenantId);
        return destinationMapper.toDto(saved);
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void delete(Long destinationId) {
        Destination destination = findOrThrow(destinationId);
        destinationRepository.delete(destination);   // cascades to cities + their children
        log.info("Destination deleted | id={} | tenantId={}", destinationId, destination.getTenantId());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** FIX: was named getVisibleDestination() which didn't exist. */
    private Destination findOrThrow(Long destinationId) {
        Long tenantId = GeographySupport.currentTenantId();
        return destinationRepository.findByIdAndTenantId(destinationId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Destination not found: " + destinationId));
    }

    private Country resolveCountry(Long countryId, Long tenantId) {
        return countryRepository.findByIdAndTenantId(countryId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Country not found: " + countryId));
    }
}