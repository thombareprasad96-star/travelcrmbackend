package com.crm.travelcrm.lead.service;

import com.crm.travelcrm.lead.dto.AddLeadLogRequestDto;
import com.crm.travelcrm.lead.dto.LeadLogResponseDto;
import com.crm.travelcrm.lead.dto.LeadLogStatsDto;
import com.crm.travelcrm.lead.dto.LeadLogSummaryResponseDto;

import java.util.List;
import java.util.UUID;

public interface LeadLogService {

    /**
     * Add an activity log to the lead identified by {@code leadPublicId}, optionally creating a
     * follow-up reminder. The lead is resolved under tenant + row-level scope first.
     */
    LeadLogResponseDto addLog(UUID leadPublicId, AddLeadLogRequestDto request);

    /** All logs for one lead, newest first. Lead resolved under tenant + row-level scope. */
    List<LeadLogResponseDto> getLogsForLead(UUID leadPublicId);

    /** Leads (visible to the caller) that have at least one log, with their latest log + count. */
    LeadLogSummaryResponseDto getLogSummary(String search, String stage, UUID userPublicId,
                                            int page, int perPage);

    /** Roll-up totals for the All-Lead-Logs stat cards. */
    LeadLogStatsDto getLogStats();

    /** Soft-delete one log belonging to the given lead. Lead resolved under tenant + scope. */
    void deleteLog(UUID leadPublicId, UUID logPublicId);
}