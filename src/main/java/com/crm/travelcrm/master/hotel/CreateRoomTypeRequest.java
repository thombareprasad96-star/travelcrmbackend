package com.crm.travelcrm.master.hotel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateRoomTypeRequest {

    @NotBlank
    private String name;

    private String size;
    private Integer occupancy;
    private String bedType;
    @DecimalMin(value = "0.0", message = "Room rate cannot be negative")
    @Digits(integer = 13, fraction = 2, message = "Room rate is invalid")
    private BigDecimal baseRate;
    private String description;
}
