package com.crm.travelcrm.master.cruise;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateCruiseRoomTypeRequest {

    private String name;
    private Integer capacity;
    private BigDecimal price;
}