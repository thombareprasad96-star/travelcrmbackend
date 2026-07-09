package com.crm.travelcrm.platform.analytics.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.platform.analytics.dto.AnalyticsOverviewResponse;
import com.crm.travelcrm.platform.analytics.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only platform analytics for the SuperAdmin dashboard. Guarded by {@code ROLE_SUPER_ADMIN}. */
@RestController
@RequestMapping("/api/super-admin/analytics")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<AnalyticsOverviewResponse>> overview() {
        return ResponseEntity.ok(ApiResponse.success("Analytics fetched", analyticsService.overview()));
    }
}