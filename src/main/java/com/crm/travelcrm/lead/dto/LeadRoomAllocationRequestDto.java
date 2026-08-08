package com.crm.travelcrm.lead.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class LeadRoomAllocationRequestDto {

    @NotNull
    @Min(1)
    private Integer roomNumber;

    @Size(max = 80)
    private String roomCategoryPreference;

    @Size(max = 80)
    private String bedPreference;

    @NotNull
    @Min(0)
    private Integer adults;

    @NotNull
    @Min(0)
    private Integer children;

    @NotNull
    @Min(0)
    private Integer infants;

    @NotNull
    @Min(0)
    private Integer extraBeds;

    @Size(max = 8)
    private List<@Min(0) @Max(17) Integer> childAges;
}
