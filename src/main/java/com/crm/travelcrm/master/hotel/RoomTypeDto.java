package com.crm.travelcrm.master.hotel;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoomTypeDto {

    Long roomTypeId;
    UUID publicId;
    Long hotelId;
    String name;
    String size;
    Integer occupancy;
    String bedType;
    BigDecimal baseRate;
    String description;
    List<String> images;
    boolean active;
    UUID platformSourcePublicId;

    /**
     * True when the marketplace catalog owns this row's descriptive fields. {@code baseRate} stays
     * tenant-owned and editable because catalog synchronization deliberately never writes it.
     * False for a room type the tenant added themselves, even on an imported hotel.
     */
    boolean platformOwned;
}
