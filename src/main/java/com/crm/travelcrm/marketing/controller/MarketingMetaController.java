package com.crm.travelcrm.marketing.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.marketing.dto.MergeTagDef;
import com.crm.travelcrm.marketing.service.MarketingFieldCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Cross-cutting Marketing metadata not tied to a single aggregate — the merge tags the message
 * composer advertises. Read-only; {@code MARKETING_READ}.
 */
@RestController
@RequestMapping("/api/marketing")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('MARKETING_READ')")
public class MarketingMetaController {

    private final MarketingFieldCatalog fieldCatalog;

    @GetMapping("/merge-tags")
    public ResponseEntity<ApiResponse<List<MergeTagDef>>> mergeTags() {
        return ResponseEntity.ok(ApiResponse.success("Merge tags fetched successfully", fieldCatalog.mergeTags()));
    }
}