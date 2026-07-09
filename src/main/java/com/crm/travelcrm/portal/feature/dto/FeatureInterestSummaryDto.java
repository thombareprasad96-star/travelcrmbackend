package com.crm.travelcrm.portal.feature.dto;

import com.crm.travelcrm.portal.feature.PortalFeatureKey;
import lombok.AllArgsConstructor;
import lombok.Data;

/** Staff-side aggregate: how many travelers registered interest in each teaser feature. */
@Data
@AllArgsConstructor
public class FeatureInterestSummaryDto {
    private PortalFeatureKey featureKey;
    private Long count;
}