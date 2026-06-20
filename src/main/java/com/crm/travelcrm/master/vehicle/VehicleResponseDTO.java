package com.crm.travelcrm.master.vehicle;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VehicleResponseDTO {

    // External-facing identifier — never expose the internal Long id
    private UUID publicId;
    private String name;
    private String type;
    private Integer capacity;
    private String description;
    private String imagePath;
    private boolean global;
    private LocalDateTime createdAt;
}