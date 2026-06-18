package com.crm.travelcrm.master.cruise;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateCruiseRoomTypeRequest {

    @NotBlank
    private String name;

    private Integer capacity;
    private BigDecimal price;
}