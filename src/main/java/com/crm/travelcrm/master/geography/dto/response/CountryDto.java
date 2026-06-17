package com.crm.travelcrm.master.geography.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.UUID;

/** Read model for a country. {@code countryId} is the primary identifier. */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CountryDto {

    Long countryId;
    UUID publicId;
    String name;
    String code;
    String description;
    String flag;
    String timezone;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}