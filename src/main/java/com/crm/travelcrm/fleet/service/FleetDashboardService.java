package com.crm.travelcrm.fleet.service;

import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.fleet.dto.FleetDashboardDto;
import com.crm.travelcrm.fleet.dto.FleetDocumentAlertDto;

public interface FleetDashboardService {

    /** Live counts + ongoing trips + expiring documents + service-due vehicles (computed on demand). */
    FleetDashboardDto dashboard();

    /** History of fired document-expiry alerts (newest first). */
    PagedApiResponse<FleetDocumentAlertDto> alerts(int page, int size);
}