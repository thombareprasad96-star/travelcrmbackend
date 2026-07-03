package com.crm.travelcrm.fleet.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.fleet.dto.FleetDashboardDto;
import com.crm.travelcrm.fleet.dto.FleetDocumentAlertDto;
import com.crm.travelcrm.fleet.service.FleetDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/fleet")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('FLEET_READ')")
public class FleetDashboardController {

    private final FleetDashboardService dashboardService;

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<FleetDashboardDto>> dashboard() {
        return ResponseEntity.ok(ApiResponse.success("Fleet dashboard fetched successfully",
                dashboardService.dashboard()));
    }

    @GetMapping("/alerts")
    public ResponseEntity<PagedApiResponse<FleetDocumentAlertDto>> alerts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(dashboardService.alerts(page, size));
    }
}