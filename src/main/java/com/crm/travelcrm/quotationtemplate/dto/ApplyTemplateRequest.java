package com.crm.travelcrm.quotationtemplate.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

/** Clone a template into a fresh DRAFT quotation for this lead. */
@Data
public class ApplyTemplateRequest {

    @NotNull(message = "leadId is required")
    private UUID leadId;

    /** Defaults to the template's name. */
    @Size(max = 200)
    private String title;
}