package com.crm.travelcrm.lead.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Paginated All-Lead-Logs grid payload. */
@Data
@Builder
public class LeadLogSummaryResponseDto {
    private List<LeadLogCardDto> leads;
    private long total;
    private int page;
    private int perPage;
    private int totalPages;
}