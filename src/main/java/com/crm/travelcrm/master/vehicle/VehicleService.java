package com.crm.travelcrm.master.vehicle;

import org.springframework.data.domain.Page;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface VehicleService {

    VehicleResponseDTO createVehicle(VehicleRequestDTO request);

    Page<VehicleResponseDTO> getAllVehicles(int page, int size, String sortBy, String sortDir);

    VehicleResponseDTO getVehicleByPublicId(UUID publicId);

    VehicleResponseDTO updateVehicle(UUID publicId, VehicleRequestDTO request);

    void deleteVehicle(UUID publicId);

    String uploadVehicleImage(MultipartFile file);

    List<VehicleResponseDTO> filterByType(String type);

    List<VehicleResponseDTO> searchVehicles(String q);
}