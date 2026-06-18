package com.crm.travelcrm.master.addon;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateAddonRequest {

    private Long cityId;

    @NotBlank
    private String name;

    private String description;
    private BigDecimal price;
    private boolean active = true;
}