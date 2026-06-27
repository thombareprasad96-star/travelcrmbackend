package com.crm.travelcrm.report.intldomestic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** One panel (International or Domestic). {@code avgNights}/{@code growthPct} have no source and are 0. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TripTypeDataDTO {
    private BigDecimal totalRevenue;
    private long       totalBookings;
    private BigDecimal avgValue;
    private double     avgNights;
    private BigDecimal tcs;
    private double     growthPct;
    private List<DestinationDTO> destinations;
}