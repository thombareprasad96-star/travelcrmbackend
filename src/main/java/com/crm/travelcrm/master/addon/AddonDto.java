package com.crm.travelcrm.master.addon;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AddonDto {

    Long addonId;
    UUID publicId;

    Long cityId;
    String cityName;
    Long destinationId;
    String destinationName;

    String name;
    String description;
    BigDecimal price;
    boolean active;

    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}