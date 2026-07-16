package com.crm.travelcrm.subagent.dto;

import com.crm.travelcrm.subagent.enums.MarkupType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * A sub-agent's OWN commission view (self-service): their configured broker rate, total earned
 * (net of reversals), and each accrual/reversal row. Reuses {@link SubAgentCommissionLedgerDto.Entry}
 * for the rows. This is what a SUB_AGENT sees about themselves — never a peer's earnings.
 */
@Data
@Builder
public class MyCommissionResponse {

    private String name;
    /** How the commission is expressed: PERCENT of sale, or FIXED per booking. */
    private MarkupType commissionType;
    private BigDecimal commissionRate;
    private BigDecimal totalEarned;
    private List<SubAgentCommissionLedgerDto.Entry> entries;
}