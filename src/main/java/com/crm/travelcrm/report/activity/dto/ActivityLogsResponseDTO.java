package com.crm.travelcrm.report.activity.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/** Paginated logs payload — read as {@code res.data.logs} / {@code res.data.total} by the FE. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityLogsResponseDTO {
    private List<ActivityLogDTO> logs;
    private long total;
    private int  page;
    private int  perPage;
    private int  totalPages;
}