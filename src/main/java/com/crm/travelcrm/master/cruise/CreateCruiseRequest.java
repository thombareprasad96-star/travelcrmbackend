package com.crm.travelcrm.master.cruise;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class CreateCruiseRequest {

    private Long cityId;

    @NotBlank
    private String name;

    private String description;
    private String image;
    private List<CreateCruiseRoomTypeRequest> roomTypes;
}