package com.crm.travelcrm.platform.ops.controller;

import com.crm.travelcrm.auth.mfa.SuperAdminStepUpService;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.util.ClientIp;
import com.crm.travelcrm.platform.ops.service.DataExportService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Platform data exports. SuperAdmin only. */
@RestController
@RequestMapping("/api/super-admin/export")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class DataExportController {

    private final DataExportService dataExportService;
    private final SuperAdminStepUpService superAdminStepUpService;

    @GetMapping(value = "/tenants.csv", produces = "text/csv")
    public ResponseEntity<String> exportTenants(
            @AuthenticationPrincipal SuperAdmin superAdmin,
            @RequestHeader(value = SuperAdminStepUpService.MFA_CODE_HEADER, required = false) String mfaCode,
            HttpServletRequest request) {
        superAdminStepUpService.requireCode(
                superAdmin, mfaCode, "tenant registry export",
                ClientIp.resolve(request), request.getHeader("User-Agent"));
        String csv = dataExportService.exportTenantsCsv();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"tenants.csv\"")
                .body(csv);
    }
}
