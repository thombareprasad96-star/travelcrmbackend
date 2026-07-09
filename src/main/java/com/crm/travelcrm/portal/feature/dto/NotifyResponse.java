package com.crm.travelcrm.portal.feature.dto;

import com.crm.travelcrm.portal.feature.PortalFeatureKey;
import lombok.AllArgsConstructor;
import lombok.Data;

/** Result of a "Notify me" tap. {@code alreadyRegistered=true} when the row already existed. */
@Data
@AllArgsConstructor
public class NotifyResponse {
    private PortalFeatureKey featureKey;
    private boolean alreadyRegistered;
}