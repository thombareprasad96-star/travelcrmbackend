package com.crm.travelcrm.platform.subscription.service;

import com.crm.travelcrm.platform.subscription.dto.PlanResponse;
import com.crm.travelcrm.platform.subscription.dto.UpdatePlanRequest;

import java.util.List;
import java.util.UUID;

/** Platform plan catalogue management (SuperAdmin). */
public interface PlanService {

    List<PlanResponse> listPlans();

    PlanResponse getPlan(UUID publicId);

    PlanResponse updatePlan(UUID publicId, UpdatePlanRequest request);
}