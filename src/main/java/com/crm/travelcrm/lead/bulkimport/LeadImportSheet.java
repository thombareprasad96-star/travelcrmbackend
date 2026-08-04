package com.crm.travelcrm.lead.bulkimport;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A parsed spreadsheet, format-agnostic. Everything downstream of {@link LeadImportFileReader} works
 * on this, so CSV and Excel converge before any lead logic runs and neither format can grow its own
 * behaviour.
 *
 * @param rows            data rows in file order, header row excluded
 * @param presentColumns  which known columns the header actually declared — lets the service name a
 *                        missing REQUIRED column once, up front, instead of failing every row
 * @param ignoredHeaders  header cells that matched no known column, reported back so a user who
 *                        misspelled "Phon" finds out rather than silently importing 300 phone-less
 *                        rows
 */
public record LeadImportSheet(
        List<LeadImportRow> rows,
        Set<LeadImportColumn> presentColumns,
        List<String> ignoredHeaders) {

    /**
     * One data row. {@code rowNumber} is the 1-based line number <em>as the user sees it in their
     * spreadsheet</em> (header = row 1), because the whole point of the report is that they can go
     * fix the offending line.
     */
    public record LeadImportRow(int rowNumber, Map<LeadImportColumn, String> values) {

        /** Trimmed cell value, or null when absent/blank — blank and missing are the same thing here. */
        public String get(LeadImportColumn column) {
            String raw = values.get(column);
            if (raw == null) {
                return null;
            }
            String trimmed = raw.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }

        /** True when every mapped cell is blank — a trailing empty line, not a user error. */
        public boolean isBlank() {
            return values.values().stream()
                    .allMatch(v -> v == null || v.trim().isEmpty());
        }
    }
}
