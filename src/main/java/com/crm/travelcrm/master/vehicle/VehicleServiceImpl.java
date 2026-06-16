package com.crm.travelcrm.master.vehicle;

import com.crm.travelcrm.common.cloudinary.CloudinaryService;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VehicleServiceImpl implements VehicleService {

    private final VehicleRepository vehicleRepository;
    private final CloudinaryService cloudinaryService;

    @Override
    @Transactional
    public VehicleResponseDTO createVehicle(VehicleRequestDTO request) {
        VehicleEntity entity = VehicleMapper.toEntity(request);
        entity.setTenantId(TenantContext.getTenantId());
        return VehicleMapper.toResponseDTO(vehicleRepository.save(entity));
    }

    @Override
    public Page<VehicleResponseDTO> getAllVehicles(int page, int size, String sortBy, String sortDir) {
        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page, size, sort);

        Long tenantId = TenantContext.getTenantId();
        Page<VehicleEntity> vehiclePage = (tenantId == null)
                ? vehicleRepository.findAll(pageable)
                : vehicleRepository.findAllVisibleTo(tenantId, pageable);

        return vehiclePage.map(VehicleMapper::toResponseDTO);
    }

    @Override
    public VehicleResponseDTO getVehicleById(Long id) {
        return VehicleMapper.toResponseDTO(getVisibleVehicle(id));
    }

    @Override
    @Transactional
    public VehicleResponseDTO updateVehicle(Long id, VehicleRequestDTO request) {
        VehicleEntity entity = getEditableVehicle(id);
        VehicleMapper.updateEntity(entity, request);
        return VehicleMapper.toResponseDTO(vehicleRepository.save(entity));
    }

    @Override
    @Transactional
    public void deleteVehicle(Long id) {
        VehicleEntity entity = getEditableVehicle(id);
        vehicleRepository.delete(entity);
    }

    @Override
    @Transactional
    public String uploadVehicleImage(MultipartFile file) {
        return cloudinaryService.uploadImage(file, "vehicles");
    }

    @Override
    public List<VehicleResponseDTO> filterByType(String type) {
        Long tenantId = TenantContext.getTenantId();
        List<VehicleEntity> entities = (tenantId == null)
                ? vehicleRepository.findAll().stream()
                        .filter(v -> type.equalsIgnoreCase(v.getType()))
                        .collect(Collectors.toList())
                : vehicleRepository.findByTypeVisible(tenantId, type);
        return entities.stream().map(VehicleMapper::toResponseDTO).collect(Collectors.toList());
    }

    @Override
    public List<VehicleResponseDTO> searchVehicles(String q) {
        Long tenantId = TenantContext.getTenantId();
        List<VehicleEntity> entities = (tenantId == null)
                ? vehicleRepository.findAll().stream()
                        .filter(v -> v.getName() != null && v.getName().toLowerCase().contains(q.toLowerCase()))
                        .collect(Collectors.toList())
                : vehicleRepository.searchByName(tenantId, q);
        return entities.stream().map(VehicleMapper::toResponseDTO).collect(Collectors.toList());
    }

    private VehicleEntity getVisibleVehicle(Long id) {
        VehicleEntity vehicle = vehicleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found with id: " + id));

        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null
                && vehicle.getTenantId() != null
                && !vehicle.getTenantId().equals(tenantId)) {
            throw new ResourceNotFoundException("Vehicle not found with id: " + id);
        }
        return vehicle;
    }

    private VehicleEntity getEditableVehicle(Long id) {
        VehicleEntity vehicle = getVisibleVehicle(id);

        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null && !tenantId.equals(vehicle.getTenantId())) {
            throw new BusinessException(
                    "Global vehicles can only be modified by the platform admin",
                    HttpStatus.FORBIDDEN);
        }
        return vehicle;
    }
}