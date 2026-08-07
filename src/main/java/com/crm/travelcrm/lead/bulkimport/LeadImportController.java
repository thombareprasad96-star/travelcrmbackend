package com.crm.travelcrm.lead.bulkimport;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.lead.bulkimport.dto.LeadImportReportDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Bulk lead import — CSV / Excel.
 *
 * <p>Two steps on purpose. {@code /preview} parses and validates the whole file and writes nothing;
 * {@code /import} then creates the rows. A mis-mapped file (wrong column order, a stage vocabulary
 * from another CRM) is caught while it is still just a report, instead of becoming 300 real leads
 * somebody has to find and trash.
 *
 * <p><b>Every route is gated on {@code LEAD_CREATE}</b> — the same authority the create form needs.
 * This is a separate controller from {@code LeadController} only to keep that class's read-by-default
 * class-level rule from applying here by accident; there is no separate permission for importing,
 * because the capability is exactly "create leads, many at once".
 */
@Slf4j
@RestController
@RequestMapping("/api/leads/import")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('LEAD_CREATE')")
public class LeadImportController {

    private final LeadImportService leadImportService;
    private final LeadImportTemplateWriter templateWriter;

    /** Dry run: validates every row and reports what would happen. Writes nothing. */
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<LeadImportReportDto>> preview(
            @RequestParam("file") MultipartFile file) {

        // No filename in the log line: a user's file name routinely carries a client or campaign name.
        log.info("Lead import preview requested | size: {} bytes", file.getSize());
        LeadImportReportDto report = leadImportService.preview(file);
        return ResponseEntity.ok(ApiResponse.success("Import preview ready", report));
    }

    /** Creates every valid, non-duplicate row. Partial success is normal — read the report. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<LeadImportReportDto>> importLeads(
            @RequestParam("file") MultipartFile file) {

        log.info("Lead import requested | size: {} bytes", file.getSize());
        LeadImportReportDto report = leadImportService.importLeads(file);
        log.info("Lead import finished | imported: {} | duplicates: {} | invalid: {} | skipped: {}",
                report.getImportedCount(), report.getDuplicateCount(),
                report.getInvalidCount(), report.getSkippedCount());

        return ResponseEntity.ok(ApiResponse.success(
                report.getImportedCount() + " of " + report.getTotalRows() + " leads imported",
                report));
    }

    /** The blank sheet, with the exact headers the parser expects and one example row. */
    @GetMapping("/template")
    public ResponseEntity<byte[]> downloadTemplate() {
        byte[] csv = templateWriter.buildCsvTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"lead-import-template.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }
}
