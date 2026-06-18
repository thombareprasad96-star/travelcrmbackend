package com.crm.travelcrm.master.geography.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Read model returned for single-item endpoints (GET by id, POST, PUT). */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DestinationDto {

    Long destinationId;

    Long countryId;
    String countryName;

    String name;
    String description;
    String type;

    String imagePath;           // ← was imageUrl — matches entity field name

    BigDecimal price;

    String inclusions;
    String exclusions;
    String paymentPolicies;
    String cancellationPolicies;
    String bookingTerms;
    String status;

    boolean global;

    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}


