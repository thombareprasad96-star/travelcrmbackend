package com.crm.travelcrm.master.hotel.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RoomTypeRequestDTO {

    @NotBlank(message = "Room name is required")
    private String name;

    private String size;

    @NotNull(message = "Occupancy is required")
    private Integer occupancy;

    private String bedType;
    private String description;
}
