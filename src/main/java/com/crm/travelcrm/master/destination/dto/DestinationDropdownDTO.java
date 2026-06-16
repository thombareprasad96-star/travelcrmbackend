package com.crm.travelcrm.master.destination.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DestinationDropdownDTO {
    private Long id;
    private String name;
    private String country;
    private String type;
    // Optional: add only if you need to filter/display
    // private boolean global;
}