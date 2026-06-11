package com.crm.travelcrm.master.city;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CityMasterResponseDTO {
    private Long id;
    private String country;
    private String name;
    private String code;
    private String status;
    // true = platform-managed city shared with all tenants (read-only for tenants)
    private boolean global;
    private LocalDateTime createdAt;
}