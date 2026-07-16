package com.crm.travelcrm.accounting.export.controller;

import com.crm.travelcrm.accounting.export.spi.LedgerExport;
import com.crm.travelcrm.accounting.export.spi.LedgerExportProvider;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/accounting/export")
@RequiredArgsConstructor
public class LedgerExportController {

    private final LedgerExportProvider exportProvider;

    @GetMapping
    @PreAuthorize("hasAuthority('ACCOUNTING_INVOICE_READ')")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "CSV") String format,
            @AuthenticationPrincipal User currentUser) {
        LedgerExport result = exportProvider.export(currentUser.getTenantId(), from, to, format);
        if (!result.available()) {
            throw new BusinessException(result.message(), HttpStatus.NOT_IMPLEMENTED);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.filename() + "\"")
                .contentType(MediaType.parseMediaType(result.contentType()))
                .body(result.content());
    }
}