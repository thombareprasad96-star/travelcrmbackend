package com.crm.travelcrm.report.traveldate.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/** Paginated period rows — read as {@code res.data.rows} / {@code res.data.total} by the FE. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PeriodAnalysisResponseDTO {
    private List<PeriodRowDTO> rows;
    private long total;
    private int  page;
    private int  perPage;
    private int  totalPages;
}