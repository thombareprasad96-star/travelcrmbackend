package com.crm.travelcrm.subagent.dto;

import com.crm.travelcrm.subagent.enums.MarkupType;
import com.crm.travelcrm.subagent.enums.SubAgentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubAgentResponse {

    /** The profile's publicId — the id the sub-agent management API speaks. */
    private UUID publicId;
    /** The underlying login User's publicId. */
    private UUID userPublicId;

    private String name;
    /** The partner's login identifier. */
    private String username;
    private String email;
    private String phoneNumber;

    private MarkupType markupType;
    private BigDecimal markupValue;

    private SubAgentStatus status;
    private boolean active;

    private String brandName;
    private String logoUrl;
    private String contactPhone;
    private String contactEmail;
    private String brandColor;

    private LocalDateTime createdAt;
}
