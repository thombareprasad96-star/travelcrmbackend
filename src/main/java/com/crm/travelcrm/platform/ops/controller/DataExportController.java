package com.crm.travelcrm.platform.ops.controller;

import com.crm.travelcrm.platform.ops.service.DataExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform data exports. SuperAdmin only. */
@RestController
@RequestMapping("/api/super-admin/export")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class DataExportController {

    private final DataExportService dataExportService;

    @GetMapping(value = "/tenants.csv", produces = "text/csv")
    public ResponseEntity<String> exportTenants() {
        String csv = dataExportService.exportTenantsCsv();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"tenants.csv\"")
                .body(csv);
    }
}
