package com.crm.travelcrm.master.vehicle;

import org.springframework.data.domain.Page;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface VehicleService {

    VehicleResponseDTO createVehicle(VehicleRequestDTO request);

    Page<VehicleResponseDTO> getAllVehicles(int page, int size, String sortBy, String sortDir);

    VehicleResponseDTO getVehicleById(Long id);

    VehicleResponseDTO updateVehicle(Long id, VehicleRequestDTO request);

    void deleteVehicle(Long id);

    String uploadVehicleImage(MultipartFile file);

    List<VehicleResponseDTO> filterByType(String type);

    List<VehicleResponseDTO> searchVehicles(String q);
}