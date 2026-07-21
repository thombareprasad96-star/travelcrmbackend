package com.crm.travelcrm.report.dashboard.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.report.dashboard.dto.DashboardAnalyticsResponse;
import com.crm.travelcrm.report.dashboard.service.DashboardAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('CRM_FULL')")
public class DashboardAnalyticsController {

    private final DashboardAnalyticsService dashboardAnalyticsService;

    @GetMapping("/analytics")
    public ResponseEntity<ApiResponse<DashboardAnalyticsResponse>> getAnalytics(
            @RequestParam(defaultValue = "month") String period,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        DashboardAnalyticsResponse analytics = dashboardAnalyticsService.getAnalytics(period, from, to);
        return ResponseEntity.ok(ApiResponse.success("Dashboard analytics fetched successfully", analytics));
    }
}
