package com.crm.travelcrm.master.hotel.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RoomTypeResponseDTO {
    private Long id;
    private String name;
    private String size;
    private Integer occupancy;
    private String bedType;
    private String description;
    private List<String> images;
}
