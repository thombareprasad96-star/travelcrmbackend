package com.crm.travelcrm.master.hotel;

import lombok.Data;

@Data
public class UpdateRoomTypeRequest {

    private String name;
    private String size;
    private Integer occupancy;
    private String bedType;
    private String description;
}