package com.crm.travelcrm.platform.config.dto;

import lombok.Data;

/** Upsert a platform config value (SuperAdmin). */
@Data
public class UpdateConfigRequest {
    private String value;
    private String description;
}