package com.crm.travelcrm.report.intldomestic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Combined payload for the {@code /all} endpoint: both panels plus the distribution. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntlDomesticResponseDTO {
    private TripTypeDataDTO international;
    private TripTypeDataDTO domestic;
    private DistributionDTO distribution;
}