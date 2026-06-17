package com.crm.travelcrm.master.geography.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Lightweight read model returned by GET /api/destinations (paginated list). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DestinationListResponseDTO {

    private Long id;
    private String country;             // country name string for display
    private String name;
    private String type;
    private String imagePath;           // ← was imageUrl — matches entity + frontend
    private String inclusions;
    private String exclusions;
    private String paymentPolicies;
    private String cancellationPolicies;
    private String bookingTerms;
    private String status;

    /** true = platform-managed, read-only for tenant users. */
    private boolean global;

    private LocalDateTime createdAt;
}