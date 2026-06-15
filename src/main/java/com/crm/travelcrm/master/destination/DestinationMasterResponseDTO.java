package com.crm.travelcrm.master.destination;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DestinationMasterResponseDTO {

    private Long id;
    private String country;
    private String name;
    private String type;
    private String imagePath;
    private String inclusions;
    private String exclusions;
    private String paymentPolicies;
    private String cancellationPolicies;
    private String bookingTerms;
    private String status;
    // true = platform-managed destination shared with all tenants (read-only for tenants)
    private boolean global;
    private LocalDateTime createdAt;
}