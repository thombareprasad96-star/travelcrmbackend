package com.crm.travelcrm.master.cruise;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.UUID;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CruiseRoomTypeDto {

    Long roomTypeId;
    UUID publicId;
    Long cruiseId;
    String name;
    Integer capacity;
    BigDecimal price;
}