package com.crm.travelcrm.subagent.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** One sub-agent's commission ledger drill-down: the running total plus each accrual/reversal row. */
@Data
@Builder
public class SubAgentCommissionLedgerDto {

    private UUID subAgentPublicId;   // SubAgentProfile.publicId
    private String name;
    private BigDecimal totalEarned;  // Σ of all entry amounts (net of reversals)
    private List<Entry> entries;

    @Data
    @Builder
    public static class Entry {
        private String bookingCode;
        private BigDecimal amount;    // signed: + earned, − reversed
        private String note;
        private LocalDate occurredOn;
        private LocalDateTime createdAt;
    }
}
