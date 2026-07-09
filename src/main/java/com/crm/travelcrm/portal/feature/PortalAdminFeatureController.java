package com.crm.travelcrm.portal.feature;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.portal.feature.dto.FeatureInterestSummaryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Staff-side, read-only summary of traveler "Notify me" interest. Lives under {@code /api/portal-admin/**}
 * which does NOT match the portal chain's {@code /api/portal/**} matcher, so it falls through to the
 * staff security chain and requires a staff JWT — a traveler token cannot reach it. No staff UI ships
 * with this phase; the endpoint is here for when the admin widget is built (see TODO.md).
 */
@RestController
@RequestMapping("/api/portal-admin/feature-interest")
@RequiredArgsConstructor
public class PortalAdminFeatureController {

    private final PortalFeatureService service;

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<List<FeatureInterestSummaryDto>>> summary() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("TenantContext is empty — cannot read feature interest.");
        }
        return ResponseEntity.ok(
                ApiResponse.success("Feature interest summary", service.summary(tenantId)));
    }
}