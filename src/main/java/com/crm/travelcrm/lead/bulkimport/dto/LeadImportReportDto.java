package com.crm.travelcrm.lead.bulkimport.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * The result of a preview or a commit. One shape for both, so the frontend renders the same table
 * twice and the numbers the user saw in the preview are directly comparable to what they got.
 */
@Data
@Builder
public class LeadImportReportDto {

    /** False for the preview (nothing was written), true after a commit. */
    private boolean committed;

    private int totalRows;

    /** Preview: rows that would import. Commit: rows that did. */
    private int readyCount;
    private int importedCount;
    private int duplicateCount;
    private int invalidCount;
    private int skippedCount;

    /**
     * Header cells that matched no known column, so the user learns that their "Mobile No" column was
     * dropped instead of wondering why every phone is missing.
     */
    private List<String> ignoredHeaders;

    /**
     * Leads the tenant's plan still allows, or null when the plan is unlimited. Shown in the preview
     * so a 400-row file against 50 remaining slots is visible BEFORE the import, not half-way through.
     */
    private Integer remainingQuota;

    private List<LeadImportRowResultDto> rows;
}
