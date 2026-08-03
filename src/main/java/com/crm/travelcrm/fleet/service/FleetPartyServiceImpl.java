package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.fleet.dto.FleetPartyRequestDto;
import com.crm.travelcrm.fleet.dto.FleetPartyResponseDto;
import com.crm.travelcrm.fleet.entity.FleetCounterparty;
import com.crm.travelcrm.fleet.repository.FleetCounterpartyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class FleetPartyServiceImpl implements FleetPartyService {

    private final FleetCounterpartyRepository repository;

    @Override
    @Transactional
    public FleetPartyResponseDto create(FleetPartyRequestDto request) {
        Long tenantId = FleetContext.tenantId();
        String name = request.getName().trim();

        // A duplicate owner is how one party's vehicles end up split across two payout statements.
        if (repository.existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNull(tenantId, name)) {
            throw new BusinessException(
                    "A party named \"" + name + "\" already exists", HttpStatus.CONFLICT);
        }

        FleetCounterparty party = FleetCounterparty.builder().build();
        party.setTenantId(tenantId);
        apply(party, request, name);
        party.setActive(request.getActive() == null || request.getActive());

        FleetCounterparty saved = repository.save(party);
        log.info("Fleet party created | id: {} | name: {} | tenantId: {}",
                saved.getId(), saved.getName(), tenantId);
        return toDto(saved, tenantId);
    }

    @Override
    @Transactional
    public FleetPartyResponseDto update(UUID publicId, FleetPartyRequestDto request) {
        Long tenantId = FleetContext.tenantId();
        FleetCounterparty party = findOrThrow(publicId, tenantId);
        String name = request.getName().trim();

        if (!party.getName().equalsIgnoreCase(name)
                && repository.existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNull(tenantId, name)) {
            throw new BusinessException(
                    "A party named \"" + name + "\" already exists", HttpStatus.CONFLICT);
        }

        apply(party, request, name);
        if (request.getActive() != null) party.setActive(request.getActive());

        FleetCounterparty saved = repository.save(party);
        log.info("Fleet party updated | id: {} | tenantId: {}", saved.getId(), tenantId);
        return toDto(saved, tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public FleetPartyResponseDto getByPublicId(UUID publicId) {
        Long tenantId = FleetContext.tenantId();
        return toDto(findOrThrow(publicId, tenantId), tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public PagedApiResponse<FleetPartyResponseDto> list(String search, Boolean active, int page, int size) {
        Long tenantId = FleetContext.tenantId();
        Pageable pageable = PageRequest.of(page, size);

        Page<FleetCounterparty> result = StringUtils.hasText(search)
                ? repository.findByTenantIdAndDeletedAtIsNullAndNameContainingIgnoreCaseOrderByNameAsc(
                        tenantId, search.trim(), pageable)
                : repository.findByTenantIdAndDeletedAtIsNullOrderByNameAsc(tenantId, pageable);

        // Applied after paging on purpose: this is a small directory (tens of rows, not thousands),
        // and a spec for one boolean would be more machinery than the filter is worth.
        List<FleetPartyResponseDto> data = result.getContent().stream()
                .filter(p -> active == null || p.isActive() == active)
                .map(p -> toDto(p, tenantId))
                .toList();

        return PagedApiResponse.of("Parties fetched successfully", data, PaginationMeta.from(result));
    }

    @Override
    @Transactional
    public void delete(UUID publicId) {
        Long tenantId = FleetContext.tenantId();
        FleetCounterparty party = findOrThrow(publicId, tenantId);

        long vehicles = repository.countVehicles(tenantId, party.getId());
        if (vehicles > 0) {
            throw new BusinessException(
                    party.getName() + " is still the owner on " + vehicles
                            + (vehicles == 1 ? " vehicle" : " vehicles")
                            + " — mark the party inactive instead, which keeps every past record intact",
                    HttpStatus.CONFLICT);
        }

        party.softDelete(FleetContext.username());
        repository.save(party);
        log.info("Fleet party soft-deleted | id: {} | tenantId: {}", party.getId(), tenantId);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private FleetCounterparty findOrThrow(UUID publicId, Long tenantId) {
        return repository.findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Party not found: " + publicId));
    }

    private void apply(FleetCounterparty party, FleetPartyRequestDto r, String name) {
        party.setName(name);
        party.setContactPerson(trim(r.getContactPerson()));
        party.setPhone(trim(r.getPhone()));
        party.setEmail(trim(r.getEmail()));
        party.setAddress(trim(r.getAddress()));
        party.setCity(trim(r.getCity()));
        party.setGstin(upper(r.getGstin()));
        party.setPan(upper(r.getPan()));
        party.setBankName(trim(r.getBankName()));
        party.setAccountName(trim(r.getAccountName()));
        party.setAccountNumber(trim(r.getAccountNumber()));
        party.setIfscCode(upper(r.getIfscCode()));
        party.setUpiId(trim(r.getUpiId()));
        party.setAgreedRate(r.getAgreedRate());
        party.setNotes(trim(r.getNotes()));
    }

    private FleetPartyResponseDto toDto(FleetCounterparty p, Long tenantId) {
        return new FleetPartyResponseDto(
                p.getPublicId(), p.getName(), p.getContactPerson(), p.getPhone(), p.getEmail(),
                p.getAddress(), p.getCity(), p.getGstin(), p.getPan(),
                p.getBankName(), p.getAccountName(), p.getAccountNumber(), p.getIfscCode(), p.getUpiId(),
                p.getAgreedRate(), p.getNotes(), p.isActive(),
                repository.countVehicles(tenantId, p.getId()),
                p.getCreatedAt());
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** GSTIN / PAN / IFSC are case-insensitive identifiers that everyone types inconsistently. */
    private static String upper(String s) {
        String t = trim(s);
        return t == null ? null : t.toUpperCase();
    }
}
