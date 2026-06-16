package com.crm.travelcrm.master.hotel.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class DestinationWithHotelsDTO {
    private Long id;
    private String name;
    private List<String> cities;
    private List<HotelResponseDTO> hotels;
}