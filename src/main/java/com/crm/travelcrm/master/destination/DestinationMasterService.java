package com.crm.travelcrm.master.destination;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.master.destination.dto.DestinationDropdownDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DestinationMasterService {

    private final DestinationMasterRepository destinationMasterRepository;

    private final DestinationMapper destinationMapper;

    public void saveDestination(DestinationMasterRequestDTO request) {
        DestinationMasterEntity entity = DestinationMapper.toEntity(request);
        // tenantId is derived from the authenticated principal — NEVER from the request body.
        // SuperAdmin has no tenant in context → global destination (visible to all tenants);
        // a tenant admin → the destination is scoped to their own tenant.
        entity.setTenantId(TenantContext.getTenantId());

        destinationMasterRepository.save(entity);
    }

    public Page<DestinationMasterResponseDTO> getAllDestinations(
            int page, int size, String sortBy, String sortDir) {

        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page, size, sort);

        Long tenantId = TenantContext.getTenantId();
        // SuperAdmin (no tenant) sees every destination across all tenants;
        // a tenant user sees global destinations merged with their own only.
        Page<DestinationMasterEntity> destinationPage = (tenantId == null)
                ? destinationMasterRepository.findAll(pageable)
                : destinationMasterRepository.findAllVisibleTo(tenantId, pageable);

        return destinationPage.map(DestinationMapper::toResponseDTO);
    }

    public DestinationMasterResponseDTO getDestinationById(Long id) {
        return DestinationMapper.toResponseDTO(getVisibleDestination(id));
    }

    public void updateDestination(Long id, DestinationMasterRequestDTO request) {
        DestinationMasterEntity entity = getEditableDestination(id);

        DestinationMapper.updateEntity(entity, request);
        destinationMasterRepository.save(entity);
    }
    public List<DestinationDropdownDTO> getDestinationsForDropdown() {
        return destinationMasterRepository.findAll()
                .stream()
                .map(destinationMapper::toDropdownDTO)
                .toList();
    }

    public void deleteDestination(Long id) {
        DestinationMasterEntity entity = getEditableDestination(id);

        destinationMasterRepository.delete(entity);
    }

    /** Global destinations and the caller's own are visible; other tenants' are not. */
    private DestinationMasterEntity getVisibleDestination(Long id) {
        DestinationMasterEntity destination = destinationMasterRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Destination not found with id: " + id));

        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null
                && destination.getTenantId() != null
                && !destination.getTenantId().equals(tenantId)) {
            // Hide other tenants' destinations — report as not found, never as forbidden,
            // so their existence is not leaked across tenant boundaries.
            throw new ResourceNotFoundException("Destination not found with id: " + id);
        }
        return destination;
    }

    /** SuperAdmin may edit anything; a tenant may edit only its own destinations (global ones are read-only). */
    private DestinationMasterEntity getEditableDestination(Long id) {
        DestinationMasterEntity destination = getVisibleDestination(id);

        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null && !tenantId.equals(destination.getTenantId())) {
            throw new BusinessException(
                    "Global destinations can only be modified by the platform admin",
                    HttpStatus.FORBIDDEN);
        }
        return destination;
    }
}