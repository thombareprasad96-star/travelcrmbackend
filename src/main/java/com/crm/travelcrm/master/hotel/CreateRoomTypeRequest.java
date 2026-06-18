package com.crm.travelcrm.master.hotel;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateRoomTypeRequest {

    @NotBlank
    private String name;

    private String size;
    private Integer occupancy;
    private String bedType;
    private String description;
}