package com.crm.travelcrm.platform.analytics.service;

import com.crm.travelcrm.platform.analytics.dto.AnalyticsOverviewResponse;

/** Read-only platform analytics for the SuperAdmin dashboard. */
public interface AnalyticsService {
    AnalyticsOverviewResponse overview();
}