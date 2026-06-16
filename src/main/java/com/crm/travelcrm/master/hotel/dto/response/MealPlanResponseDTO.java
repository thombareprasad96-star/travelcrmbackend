package com.crm.travelcrm.master.hotel.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MealPlanResponseDTO {
    private Long id;
    private String name;
    private Double price;
    private String description;
}