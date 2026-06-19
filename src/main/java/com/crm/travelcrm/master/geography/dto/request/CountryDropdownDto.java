package com.crm.travelcrm.master.geography.dto.response;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
public class CountryDropdownDto {

    Long   countryId;
    UUID publicId;    // safe external identifier
    String name;
    String code;
}