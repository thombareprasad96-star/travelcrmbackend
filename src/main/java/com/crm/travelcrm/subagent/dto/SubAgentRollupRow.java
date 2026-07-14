package com.crm.travelcrm.subagent.dto;

import com.crm.travelcrm.subagent.enums.MarkupType;
import com.crm.travelcrm.subagent.enums.SubAgentStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/** One sub-agent's line in the parent roll-up: who they are, what they've sold, and what they've earned. */
@Data
@Builder
public class SubAgentRollupRow {

    private UUID publicId;          // SubAgentProfile.publicId
    private UUID userPublicId;      // the sub-agent User.publicId
    private String name;
    private String email;
    private SubAgentStatus status;

    /** The configured commission rate (PERCENT of sale, or FIXED per booking). */
    private MarkupType commissionType;
    private BigDecimal commissionRate;

    private long bookingsCount;
    private BigDecimal totalRevenue;     // Σ booking sale value (customerAmount)
    private BigDecimal totalCollected;   // Σ paidAmount
    private BigDecimal commissionEarned; // accrued commission (net of reversals) from the ledger
}
