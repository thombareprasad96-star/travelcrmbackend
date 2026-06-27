package com.crm.travelcrm.report.geographic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/** Paginated geographic rows — read as {@code res.data.rows} / {@code res.data.total} by the FE. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeoDistributionResponseDTO {
    private List<GeoRowDTO> rows;
    private long total;
    private int  page;
    private int  perPage;
    private int  totalPages;
}