package com.crm.travelcrm.subagent.controller;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.subagent.dto.MyCommissionResponse;
import com.crm.travelcrm.subagent.service.SubAgentRollupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service commission view for a B2B sub-agent — their OWN accrued commission only. Any
 * authenticated tenant user may call it; the ledger is resolved strictly from the caller's user id
 * (never a path param), and a non-sub-agent caller gets 404. This is the sub-agent counterpart to
 * the admin-only {@code /api/subagents/rollup} + {@code /{id}/commissions} (which are USER_*-gated).
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MyCommissionController {

    private final SubAgentRollupService rollupService;

    @GetMapping("/commissions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<MyCommissionResponse>> myCommissions(@AuthenticationPrincipal User user) {
        if (user == null) {
            throw new BusinessException("Authentication required.", HttpStatus.UNAUTHORIZED);
        }
        return ResponseEntity.ok(ApiResponse.success(
                "Commission fetched", rollupService.myCommission(user.getId())));
    }
}