package com.crm.travelcrm.platform.audit.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.platform.audit.dto.PlatformAuditLogResponse;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import com.crm.travelcrm.platform.audit.service.PlatformAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Read-only view of the platform audit ledger. SuperAdmin only. */
@RestController
@RequestMapping("/api/super-admin/audit-logs")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class PlatformAuditController {

    private final PlatformAuditService auditService;

    @GetMapping
    public ResponseEntity<PagedApiResponse<PlatformAuditLogResponse>> list(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<PlatformAuditLogResponse> result =
                auditService.list(parseAction(action), success, from, to, q, pageable);
        return ResponseEntity.ok(PagedApiResponse.of(
                "Audit logs fetched", result.getContent(), PaginationMeta.from(result)));
    }

    @GetMapping("/actions")
    public ResponseEntity<ApiResponse<List<String>>> actions() {
        return ResponseEntity.ok(ApiResponse.success("Actions fetched", auditService.actions()));
    }

    /** Lenient parse — an unknown/blank action is ignored (no 400/500), the filter just drops. */
    private PlatformAuditAction parseAction(String action) {
        if (action == null || action.isBlank()) return null;
        try {
            return PlatformAuditAction.valueOf(action.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}