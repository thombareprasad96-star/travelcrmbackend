package com.crm.travelcrm.platform.subscription.service;

import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import com.crm.travelcrm.platform.subscription.dto.PlanResponse;
import com.crm.travelcrm.platform.subscription.dto.UpdatePlanRequest;
import com.crm.travelcrm.platform.subscription.entity.Plan;
import com.crm.travelcrm.platform.subscription.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlanServiceImpl implements PlanService {

    private final PlanRepository planRepository;
    private final PlatformAuditRecorder platformAuditRecorder;

    @Override
    @Transactional(readOnly = true)
    public List<PlanResponse> listPlans() {
        return planRepository.findAllByOrderByMonthlyPriceAsc().stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PlanResponse getPlan(UUID publicId) {
        return toResponse(find(publicId));
    }

    @Override
    @Transactional
    public PlanResponse updatePlan(UUID publicId, UpdatePlanRequest request) {
        Plan plan = find(publicId);
        plan.setDisplayName(request.getDisplayName());
        plan.setMonthlyPrice(request.getMonthlyPrice());
        plan.setCurrency(request.getCurrency());
        plan.setMaxUsers(request.getMaxUsers());
        plan.setMaxLeads(request.getMaxLeads());
        plan.setModules(request.getModules() != null ? new HashSet<>(request.getModules()) : new HashSet<>());
        plan.setActive(request.isActive());

        Plan saved = planRepository.save(plan);
        platformAuditRecorder.safeRecord(PlatformAuditAction.PLAN_UPDATE, true,
                null, null, "PLAN", saved.getPublicId(),
                "Updated plan " + saved.getCode() + " (" + saved.getDisplayName() + ")");
        return toResponse(saved);
    }

    private Plan find(UUID publicId) {
        return planRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Plan not found: " + publicId));
    }

    private PlanResponse toResponse(Plan p) {
        return PlanResponse.builder()
                .publicId(p.getPublicId())
                .code(p.getCode())
                .displayName(p.getDisplayName())
                .monthlyPrice(p.getMonthlyPrice())
                .currency(p.getCurrency())
                .maxUsers(p.getMaxUsers())
                .maxLeads(p.getMaxLeads())
                .modules(p.getModules())
                .active(p.isActive())
                .build();
    }
}