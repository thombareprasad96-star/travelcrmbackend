package com.crm.travelcrm.accounting.tds.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/** TDS deducted, grouped by section, over a period — the basis for a TDS return / Form 26Q. */
@Getter
@Builder
public class TdsSummaryRow {

    private final String section;
    private final String sectionLabel;
    private final long billCount;
    private final BigDecimal totalBase;
    private final BigDecimal totalTds;
}