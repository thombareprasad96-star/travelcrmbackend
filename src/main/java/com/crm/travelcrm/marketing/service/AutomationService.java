package com.crm.travelcrm.marketing.service;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.marketing.dto.AutomationResponse;
import com.crm.travelcrm.marketing.dto.SendTestRequest;
import com.crm.travelcrm.marketing.dto.UpcomingCelebrationResponse;
import com.crm.travelcrm.marketing.dto.UpdateAutomationRequest;
import com.crm.travelcrm.marketing.enums.AutomationType;

import java.util.List;

public interface AutomationService {

    ApiResponse<List<AutomationResponse>> list();

    ApiResponse<AutomationResponse> get(AutomationType type);

    ApiResponse<AutomationResponse> update(AutomationType type, UpdateAutomationRequest request);

    ApiResponse<Void> test(AutomationType type, SendTestRequest request);

    ApiResponse<List<UpcomingCelebrationResponse>> upcoming(int days);
}