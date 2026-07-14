package com.crm.travelcrm.subagent.dto;

import com.crm.travelcrm.subagent.enums.MarkupType;
import com.crm.travelcrm.subagent.enums.SubAgentStatus;
import lombok.Data;

import java.math.BigDecimal;

/** Partial update of a sub-agent — every field is optional (null = leave unchanged). */
@Data
public class UpdateSubAgentRequest {

    private String name;
    private String phoneNumber;

    private MarkupType markupType;
    private BigDecimal markupValue;

    /** ACTIVE/SUSPENDED — SUSPENDED also disables the login. */
    private SubAgentStatus status;

    /** Explicit login active toggle (independent of status, if you need it). */
    private Boolean active;

    private String brandName;
    private String logoUrl;
    private String contactPhone;
    private String contactEmail;
    private String brandColor;
}
